package io.github.janglada.orkit.booking.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.booking.AppointmentAssignment;
import io.github.janglada.orkit.booking.BookingGoal;
import io.github.janglada.orkit.booking.BookingSolution;
import io.github.janglada.orkit.internal.CpSatParams;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * Compiles a resource-booking model into CP-SAT and solves it.
 * <p>
 * Each appointment has one presence literal and one start time. Each requirement group picks
 * exactly one resource when the appointment is present, through an optional interval per
 * (group, candidate) that lives on that resource's {@code NoOverlap}. Multi-window availability
 * is modeled with one literal per window; a buffer extends the occupied interval beyond the
 * reported end. An optional tie-break prefers earlier starts without changing the goal value.
 */
public final class CpSatBookingSolver {

    private final LocalDate horizonStart;
    private final int horizonDays;
    private final BookingGoal goal;
    private final List<ResourceDef> resources;
    private final List<AppointmentDef> appointments;
    private final boolean preferEarlyStarts;
    private final CpSatParams params;
    private final Consumer<BookingSolution> listener;
    private final BookingSolution warmStart;

    /** Creates a solver for the given resources and appointments. Internal; obtain via {@link io.github.janglada.orkit.booking.BookingBuilder#solver()}. */
    public CpSatBookingSolver(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                              List<ResourceDef> resources, List<AppointmentDef> appointments, boolean preferEarlyStarts,
                              CpSatParams params, Consumer<BookingSolution> listener, BookingSolution warmStart) {
        this.horizonStart = horizonStart;
        this.horizonDays = horizonDays;
        this.goal = goal;
        this.resources = List.copyOf(resources);
        this.appointments = List.copyOf(appointments);
        this.preferEarlyStarts = preferEarlyStarts;
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }

    /** Variables of one appointment. */
    private record Vars(BoolVar present, IntVar start, List<Map<String, BoolVar>> choice) {}

    /**
     * Builds the CP-SAT model (shared start, one optional interval per requirement candidate,
     * per-resource {@code NoOverlap}) and returns the solved {@link BookingSolution}.
     */
    public BookingSolution solve() {
        validate();

        final LocalDateTime epoch = horizonStart.atStartOfDay();
        final long horizonMinutes = horizonDays * 24L * 60L;
        Map<String, ResourceDef> resourceById = new HashMap<>();
        for (ResourceDef r : resources) resourceById.put(r.id(), r);

        CpModel model = new CpModel();
        Map<String, List<IntervalVar>> onResource = new LinkedHashMap<>();
        for (ResourceDef r : resources) onResource.put(r.id(), new ArrayList<>());
        List<Vars> vars = new ArrayList<>();

        for (AppointmentDef a : appointments) {
            long startMin = (a.earliestOffset() != null) ? a.earliestOffset() : 0L;
            long endMax = (a.latestOffset() != null) ? a.latestOffset() : horizonMinutes;
            long startMax = Math.max(0, endMax - a.durationMinutes());
            // A window too tight to hold the appointment means it can never be scheduled. Force
            // absence rather than letting the domains contradict each other, which would make
            // the whole model infeasible instead of leaving one appointment unscheduled.
            boolean fits = startMax >= startMin;
            if (!fits) startMax = startMin;

            BoolVar present = model.newBoolVar("p_" + a.id());
            if (!fits) model.addEquality(present, 0);
            IntVar start = model.newIntVar(startMin, startMax, "s_" + a.id());
            LinearExpr end = LinearExpr.affine(start, 1, a.durationMinutes());
            long occupied = a.durationMinutes() + a.bufferMinutes();

            List<Map<String, BoolVar>> choice = new ArrayList<>();
            for (int g = 0; g < a.resourceGroups().size(); g++) {
                Map<String, BoolVar> picks = new LinkedHashMap<>();
                LinearExprBuilder picked = LinearExpr.newBuilder();
                for (String rid : a.resourceGroups().get(g)) {
                    if (picks.containsKey(rid)) continue;
                    String tag = a.id() + "_" + g + "_" + rid;
                    BoolVar x = model.newBoolVar("x_" + tag);
                    picks.put(rid, x);
                    picked.add(x);
                    // The resource is occupied for duration + buffer, which may spill past windows and latest().
                    onResource.get(rid).add(model.newOptionalFixedSizeIntervalVar(start, occupied, x, "iv_" + tag));
                    addAvailability(model, resourceById.get(rid).slots(), start, end, x, tag);
                }
                // One resource of every requirement when present, none when absent.
                model.addEquality(picked, present);
                choice.add(picks);
            }
            vars.add(new Vars(present, start, choice));
        }

        for (List<IntervalVar> ivs : onResource.values()) {
            if (!ivs.isEmpty()) model.addNoOverlap(ivs.toArray(new IntervalVar[0]));
        }

        long scale = objective(model, vars, horizonMinutes);
        if (warmStart != null) applyWarmStart(model, vars);

        return params.solve(model, (v, status, runtime, diag) -> {
            List<AppointmentAssignment> assignments = new ArrayList<>();
            List<String> unscheduled = new ArrayList<>();
            boolean solved = status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;
            long scheduledCount = 0;
            long unscheduledPenalty = 0;
            if (solved) {
                for (int i = 0; i < appointments.size(); i++) {
                    AppointmentDef a = appointments.get(i);
                    Vars av = vars.get(i);
                    if (!v.booleanValue(av.present())) {
                        unscheduled.add(a.id());
                        unscheduledPenalty += a.penalty();
                        continue;
                    }
                    scheduledCount++;
                    List<String> booked = new ArrayList<>();
                    for (Map<String, BoolVar> picks : av.choice()) {
                        for (var e : picks.entrySet()) {
                            if (v.booleanValue(e.getValue())) {
                                booked.add(e.getKey());
                                break;
                            }
                        }
                    }
                    long startVal = v.value(av.start());
                    assignments.add(new AppointmentAssignment(a.id(), booked.get(0),
                            epoch.plusMinutes(startVal), epoch.plusMinutes(startVal + a.durationMinutes()), booked));
                }
            }
            OptionalDouble objValue = OptionalDouble.empty();
            if (solved) {
                objValue = OptionalDouble.of(goal instanceof BookingGoal.MaximizeScheduledAppointments
                        ? scheduledCount : unscheduledPenalty);
            }
            return new BookingSolution(status, objValue, runtime, assignments, unscheduled, rescale(diag, scale));
        }, listener);
    }

    /** Restricts the appointment to lie inside one availability window of the resource when {@code x} holds. */
    private static void addAvailability(CpModel model, List<SlotWindow> slots, IntVar start, LinearExpr end,
                                        BoolVar x, String tag) {
        if (slots.size() == 1) {
            model.addGreaterOrEqual(start, slots.get(0).startMin()).onlyEnforceIf(x);
            model.addLessOrEqual(end, slots.get(0).endMin()).onlyEnforceIf(x);
        } else if (slots.size() > 1) {
            BoolVar[] inSlot = new BoolVar[slots.size()];
            for (int k = 0; k < slots.size(); k++) {
                inSlot[k] = model.newBoolVar("slot_" + tag + "_" + k);
                model.addGreaterOrEqual(start, slots.get(k).startMin()).onlyEnforceIf(inSlot[k]);
                model.addLessOrEqual(end, slots.get(k).endMin()).onlyEnforceIf(inSlot[k]);
            }
            // Exactly one window when booked, none otherwise.
            model.addEquality(LinearExpr.sum(inSlot), x);
        }
    }

    /**
     * Sets the objective and returns its scale: the goal is multiplied by {@code scale} and the
     * sum of start times, always below {@code scale}, breaks ties. {@code 1} without the tie-break.
     */
    private long objective(CpModel model, List<Vars> vars, long horizonMinutes) {
        long scale = 1;
        LinearExprBuilder starts = LinearExpr.newBuilder();
        if (preferEarlyStarts) {
            scale = Validate.add(Validate.multiply(appointments.size(), horizonMinutes, "Booking tie-break scale"), 1,
                    "Booking tie-break scale");
            for (Vars v : vars) starts.add(v.start());
        }
        LinearExprBuilder obj = LinearExpr.newBuilder();
        switch (goal) {
            case BookingGoal.MaximizeScheduledAppointments g -> {
                Validate.multiply(scale, appointments.size(), "Booking objective");
                for (Vars v : vars) obj.addTerm(v.present(), scale);
                obj.addTerm(starts.build(), -1);
                model.maximize(obj);
            }
            case BookingGoal.MinimizeUnscheduledPenalty g -> {
                long total = 0;
                for (AppointmentDef a : appointments) total = Validate.add(total, a.penalty(), "Total unscheduled penalty");
                Validate.multiply(scale, total, "Booking objective");
                for (int i = 0; i < vars.size(); i++) {
                    // penalty * (1 - present)
                    obj.add(appointments.get(i).penalty() * scale);
                    obj.addTerm(vars.get(i).present(), -appointments.get(i).penalty() * scale);
                }
                obj.addTerm(starts.build(), 1);
                model.minimize(obj);
            }
        }
        return scale;
    }

    /**
     * Maps a bound on the scaled objective back to the goal's units. The tie-break term lies in
     * {@code [0, scale)}, so a lower bound {@code b} on {@code goal * scale + starts} gives
     * {@code goal >= floor(b / scale)}, and an upper bound on {@code goal * scale - starts}
     * gives {@code goal <= ceil(b / scale)}.
     */
    private SolveDiagnostics rescale(SolveDiagnostics diag, long scale) {
        if (scale == 1 || diag.bestBound().isEmpty()) return diag;
        double ratio = diag.bestBound().getAsDouble() / scale;
        double goalBound = goal instanceof BookingGoal.MaximizeScheduledAppointments ? Math.ceil(ratio) : Math.floor(ratio);
        return new SolveDiagnostics(OptionalDouble.of(goalBound), diag.branches(), diag.conflicts(),
                diag.infeasibilityReasons());
    }

    /** Hints presence, start, and resource choices for every appointment present in a previous solution. */
    private void applyWarmStart(CpModel model, List<Vars> vars) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < appointments.size(); i++) index.put(appointments.get(i).id(), i);
        for (AppointmentAssignment prev : warmStart.assignments()) {
            Integer i = index.get(prev.appointmentId());
            if (i == null) continue;
            Vars v = vars.get(i);
            model.addHint(v.present(), 1);
            model.addHint(v.start(), Duration.between(horizonStart.atStartOfDay(), prev.start()).toMinutes());
            for (Map<String, BoolVar> picks : v.choice()) {
                for (var e : picks.entrySet()) model.addHint(e.getValue(), prev.resourceIds().contains(e.getKey()) ? 1 : 0);
            }
        }
    }

    private void validate() {
        if (horizonStart == null) {
            throw new ModelException("horizon(...) must be set before solving.");
        }
        if (resources.isEmpty()) {
            throw new ModelException("At least one resource is required.");
        }
        if (appointments.isEmpty()) {
            throw new ModelException("At least one appointment is required.");
        }
        Set<String> ridSet = new HashSet<>();
        for (ResourceDef r : resources) {
            if (!ridSet.add(r.id())) {
                throw new ModelException("Duplicate resource id: '" + r.id() + "'.");
            }
        }
        Set<String> aidSet = new HashSet<>();
        for (AppointmentDef a : appointments) {
            if (!aidSet.add(a.id())) {
                throw new ModelException("Duplicate appointment id: '" + a.id() + "'.");
            }
            if (a.durationMinutes() < 1) {
                throw new ModelException("Appointment '" + a.id() + "' has duration < 1 minute.");
            }
            if (a.resourceGroups().isEmpty() || a.resourceGroups().stream().anyMatch(List::isEmpty)) {
                throw new ModelException("Appointment '" + a.id() + "' has no candidate resources.");
            }
            for (List<String> group : a.resourceGroups()) {
                for (String rid : group) {
                    if (!ridSet.contains(rid)) {
                        throw new ModelException("Appointment '" + a.id() + "' references unknown resource '" + rid + "'.");
                    }
                }
            }
        }
    }
}

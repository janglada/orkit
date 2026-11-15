package io.github.janglada.orkit.booking.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.internal.CpSatParams;
import java.util.function.Consumer;
import io.github.janglada.orkit.booking.BookingGoal;
import io.github.janglada.orkit.booking.BookingSolution;
import io.github.janglada.orkit.booking.AppointmentAssignment;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Compiles a resource-booking model into CP-SAT (optional intervals + per-resource {@code NoOverlap}) and solves it.
 * Multi-window availability is modeled with one literal per window; a buffer extends the occupied interval
 * beyond the reported end.
 */
public final class CpSatBookingSolver {

    private final LocalDate horizonStart;
    private final int horizonDays;
    private final BookingGoal goal;
    private final List<ResourceDef> resources;
    private final List<AppointmentDef> appointments;
    private final CpSatParams params;
    private final Consumer<BookingSolution> listener;
    private final BookingSolution warmStart;


    /** Creates a solver for the given resources and appointments. Internal; obtain via {@link io.github.janglada.orkit.booking.BookingBuilder#solver()}. */
    public CpSatBookingSolver(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                              List<ResourceDef> resources, List<AppointmentDef> appointments,
                              CpSatParams params, Consumer<BookingSolution> listener, BookingSolution warmStart) {
        this.horizonStart = horizonStart;
        this.horizonDays = horizonDays;
        this.goal = goal;
        this.resources = List.copyOf(resources);
        this.appointments = List.copyOf(appointments);
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }


    /** Builds the CP-SAT model (optional intervals, per-resource {@code NoOverlap}, one-resource-per-appointment)
     *  and returns the solved {@link BookingSolution}. */
    public BookingSolution solve() {
        validate();

        final LocalDateTime epoch = horizonStart.atStartOfDay();
        final long horizonMinutes = horizonDays * 24L * 60L;

        CpModel model = new CpModel();

        // Per (appointment a, candidate resource r): presence, start, end, optional interval.
        Map<String, Map<String, BoolVar>> x = new LinkedHashMap<>();
        Map<String, Map<String, IntVar>> s = new LinkedHashMap<>();
        Map<String, Map<String, IntervalVar>> iv = new LinkedHashMap<>();

        for (AppointmentDef a : appointments) {
            Map<String, BoolVar> xForA = new LinkedHashMap<>();
            Map<String, IntVar> sForA = new LinkedHashMap<>();
            Map<String, IntervalVar> ivForA = new LinkedHashMap<>();

            for (String rid : a.resourceIds()) {
                ResourceDef r = findResource(rid);

                long startMin = (a.earliestOffset() != null) ? a.earliestOffset() : 0L;
                long endMax = (a.latestOffset() != null) ? a.latestOffset() : horizonMinutes;
                long startRangeMax = Math.max(0, endMax - a.durationMinutes());
                // A window too tight to hold the appointment (for example earliest() close to the
                // horizon end) means it can never be present on this resource. Force absence rather
                // than letting the domains contradict each other, which would make the whole model
                // infeasible instead of leaving one appointment unscheduled.
                boolean fits = startRangeMax >= startMin;
                if (!fits) startRangeMax = startMin;

                BoolVar pres = model.newBoolVar("x_" + a.id() + "_" + rid);
                if (!fits) model.addEquality(pres, 0);
                IntVar start = model.newIntVar(startMin, startRangeMax, "s_" + a.id() + "_" + rid);
                // end = start + duration is what windows and latest() constrain and what is reported;
                // the resource is occupied for duration + buffer, which may spill past both.
                long endUb = Math.max(horizonMinutes, startRangeMax + a.durationMinutes());
                IntVar end = model.newIntVar(0, endUb, "e_" + a.id() + "_" + rid);
                model.addEquality(end, LinearExpr.newBuilder().add(start).add(a.durationMinutes()).build());
                long occupied = a.durationMinutes() + a.bufferMinutes();
                IntVar occEnd = model.newIntVar(0, endUb + a.bufferMinutes(), "oe_" + a.id() + "_" + rid);
                IntervalVar interval = model.newOptionalIntervalVar(
                        start, model.newConstant(occupied), occEnd, pres,
                        "iv_" + a.id() + "_" + rid);

                // Availability windows: the appointment must lie entirely inside one of them.
                List<SlotWindow> slots = r.slots();
                if (slots.size() == 1) {
                    model.addGreaterOrEqual(start, slots.get(0).startMin()).onlyEnforceIf(pres);
                    model.addLessOrEqual(end, slots.get(0).endMin()).onlyEnforceIf(pres);
                } else if (slots.size() > 1) {
                    BoolVar[] inSlot = new BoolVar[slots.size()];
                    for (int k = 0; k < slots.size(); k++) {
                        inSlot[k] = model.newBoolVar("slot_" + a.id() + "_" + rid + "_" + k);
                        model.addGreaterOrEqual(start, slots.get(k).startMin()).onlyEnforceIf(inSlot[k]);
                        model.addLessOrEqual(end, slots.get(k).endMin()).onlyEnforceIf(inSlot[k]);
                    }
                    // Exactly one window when present, none when absent.
                    model.addEquality(LinearExpr.sum(inSlot), pres);
                }

                xForA.put(rid, pres);
                sForA.put(rid, start);
                ivForA.put(rid, interval);
            }
            x.put(a.id(), xForA);
            s.put(a.id(), sForA);
            iv.put(a.id(), ivForA);
        }

        // (1) Each appointment at most one resource.
        for (AppointmentDef a : appointments) {
            BoolVar[] presences = x.get(a.id()).values().toArray(new BoolVar[0]);
            LinearExprBuilder lb = LinearExpr.newBuilder();
            for (BoolVar p : presences) lb.add(p);
            model.addLessOrEqual(lb.build(), 1);
        }

        // (2) NoOverlap per resource.
        for (ResourceDef r : resources) {
            List<IntervalVar> ivs = new ArrayList<>();
            for (AppointmentDef a : appointments) {
                Map<String, IntervalVar> ivForA = iv.get(a.id());
                if (ivForA.containsKey(r.id())) {
                    ivs.add(ivForA.get(r.id()));
                }
            }
            if (!ivs.isEmpty()) {
                model.addNoOverlap(ivs.toArray(new IntervalVar[0]));
            }
        }

        // (3) Objective.
        if (goal instanceof BookingGoal.MaximizeScheduledAppointments) {
            LinearExprBuilder lb = LinearExpr.newBuilder();
            for (AppointmentDef a : appointments) {
                for (BoolVar p : x.get(a.id()).values()) lb.add(p);
            }
            model.maximize(lb.build());
        } else if (goal instanceof BookingGoal.MinimizeUnscheduledPenalty) {
            LinearExprBuilder lb = LinearExpr.newBuilder();
            for (AppointmentDef a : appointments) {
                BoolVar unsched = model.newBoolVar("u_" + a.id());
                BoolVar[] presences = x.get(a.id()).values().toArray(new BoolVar[0]);
                LinearExprBuilder sumLb = LinearExpr.newBuilder().add(unsched);
                for (BoolVar p : presences) sumLb.add(p);
                model.addEquality(sumLb.build(), 1);
                lb.addTerm(unsched, a.penalty());
            }
            model.minimize(lb.build());
        } else {
            throw new ModelException("Unknown goal: " + goal);
        }

        if (warmStart != null) applyWarmStart(model, x, s);

        CpSatParams.Extractor<BookingSolution> extract = (v, status, runtime) -> {

            List<AppointmentAssignment> assignments = new ArrayList<>();
            List<String> unscheduled = new ArrayList<>();
            if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
                for (AppointmentDef a : appointments) {
                    String assignedResource = null;
                    long startVal = 0;
                    for (String rid : a.resourceIds()) {
                        if (v.booleanValue(x.get(a.id()).get(rid))) {
                            assignedResource = rid;
                            startVal = v.value(s.get(a.id()).get(rid));
                            break;
                        }
                    }
                    if (assignedResource != null) {
                        assignments.add(new AppointmentAssignment(a.id(), assignedResource,
                                epoch.plusMinutes(startVal), epoch.plusMinutes(startVal + a.durationMinutes())));
                    } else {
                        unscheduled.add(a.id());
                    }
                }
            }

            OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                    ? OptionalDouble.of(v.objectiveValue())
                    : OptionalDouble.empty();

            return new BookingSolution(status, objValue, runtime, assignments, unscheduled);
        };
        return params.solve(model, extract, listener);
    }

    /** Hints resource choice and start for every appointment present in a previous solution. */
    private void applyWarmStart(CpModel model, Map<String, Map<String, BoolVar>> x, Map<String, Map<String, IntVar>> s) {
        for (AppointmentAssignment prev : warmStart.assignments()) {
            Map<String, BoolVar> xForA = x.get(prev.appointmentId());
            if (xForA == null) continue;
            for (var e : xForA.entrySet()) {
                boolean chosen = e.getKey().equals(prev.resourceId());
                model.addHint(e.getValue(), chosen ? 1 : 0);
                if (chosen) model.addHint(s.get(prev.appointmentId()).get(e.getKey()),
                        Duration.between(horizonStart.atStartOfDay(), prev.start()).toMinutes());
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
            if (a.resourceIds().isEmpty()) {
                throw new ModelException("Appointment '" + a.id() + "' has no candidate resources.");
            }
            for (String rid : a.resourceIds()) {
                if (!ridSet.contains(rid)) {
                    throw new ModelException("Appointment '" + a.id() + "' references unknown resource '" + rid + "'.");
                }
            }
        }
    }

    private ResourceDef findResource(String id) {
        for (ResourceDef r : resources) {
            if (r.id().equals(id)) return r;
        }
        throw new ModelException("Unknown resource '" + id + "'.");
    }

}

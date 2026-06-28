package opt.booking.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.booking.BookingGoal;
import opt.booking.BookingSolution;
import opt.booking.AppointmentAssignment;
import opt.booking.TimeSlot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Compiles a resource-booking model into CP-SAT (optional intervals + per-resource {@code NoOverlap}) and solves it. */
public final class CpSatBookingSolver {

    private final LocalDate horizonStart;
    private final int horizonDays;
    private final BookingGoal goal;
    private final List<ResourceDef> resources;
    private final List<AppointmentDef> appointments;

    private long timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    /** Creates a solver for the given resources and appointments. Internal; obtain via {@link opt.booking.BookingBuilder#solver()}. */
    public CpSatBookingSolver(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                              List<ResourceDef> resources, List<AppointmentDef> appointments) {
        this.horizonStart = horizonStart;
        this.horizonDays = horizonDays;
        this.goal = goal;
        this.resources = List.copyOf(resources);
        this.appointments = List.copyOf(appointments);
    }

    /** Sets the maximum time the CP-SAT solver may run. */
    public CpSatBookingSolver timeLimit(Duration limit) { this.timeLimitSeconds = limit.toSeconds(); return this; }
    /** Sets the number of parallel search workers. */
    public CpSatBookingSolver workers(int n)            { this.workers = n;                            return this; }
    /** Sets the random seed for the CP-SAT search. */
    public CpSatBookingSolver seed(long s)              { this.seed = s;                               return this; }
    /** Enables or disables CP-SAT search-progress logging. */
    public CpSatBookingSolver verbose(boolean v)        { this.verbose = v;                            return this; }

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
                if (startRangeMax < startMin) {
                    // Window too tight to fit on this resource — collapse the domain so CP-SAT forces x = 0.
                    startRangeMax = startMin;
                }

                BoolVar pres = model.newBoolVar("x_" + a.id() + "_" + rid);
                IntVar start = model.newIntVar(startMin, startRangeMax, "s_" + a.id() + "_" + rid);
                IntVar end = model.newIntVar(0, horizonMinutes, "e_" + a.id() + "_" + rid);
                IntervalVar interval = model.newOptionalIntervalVar(
                        start, model.newConstant(a.durationMinutes()), end, pres,
                        "iv_" + a.id() + "_" + rid);
                model.addEquality(end, LinearExpr.newBuilder().add(start).add(a.durationMinutes()).build());

                // Availability window: single-slot resources only (multi-slot validated out).
                if (!r.slots().isEmpty()) {
                    TimeSlot slot = r.slots().get(0);
                    long slotStart = Duration.between(epoch, slot.start()).toMinutes();
                    long slotEnd = Duration.between(epoch, slot.end()).toMinutes();
                    model.addGreaterOrEqual(start, slotStart).onlyEnforceIf(pres);
                    model.addLessOrEqual(end, slotEnd).onlyEnforceIf(pres);
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

        // Solve.
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(workers);
        solver.getParameters().setRandomSeed((int) seed);
        solver.getParameters().setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        CpSolverStatus cpStatus = solver.solve(model);
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        SolveStatus status = mapStatus(cpStatus);

        List<AppointmentAssignment> assignments = new ArrayList<>();
        List<String> unscheduled = new ArrayList<>();
        if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
            for (AppointmentDef a : appointments) {
                String assignedResource = null;
                long startVal = 0;
                for (String rid : a.resourceIds()) {
                    if (solver.booleanValue(x.get(a.id()).get(rid))) {
                        assignedResource = rid;
                        startVal = solver.value(s.get(a.id()).get(rid));
                        break;
                    }
                }
                if (assignedResource != null) {
                    assignments.add(new AppointmentAssignment(a.id(), assignedResource,
                            startVal, startVal + a.durationMinutes()));
                } else {
                    unscheduled.add(a.id());
                }
            }
        }

        OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                ? OptionalDouble.of(solver.objectiveValue())
                : OptionalDouble.empty();

        return new BookingSolution(status, objValue, runtime, assignments, unscheduled);
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
            if (r.slots().size() > 1) {
                throw new ModelException("Resource '" + r.id() + "' declares " + r.slots().size()
                        + " slots; multi-slot resources are not supported yet.");
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

    private SolveStatus mapStatus(CpSolverStatus s) {
        return switch (s) {
            case OPTIMAL       -> SolveStatus.OPTIMAL;
            case FEASIBLE      -> SolveStatus.FEASIBLE;
            case INFEASIBLE    -> SolveStatus.INFEASIBLE;
            case MODEL_INVALID -> SolveStatus.MODEL_INVALID;
            default            -> SolveStatus.UNKNOWN;
        };
    }
}

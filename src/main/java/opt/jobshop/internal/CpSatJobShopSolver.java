package opt.jobshop.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.jobshop.JobShopSchedule;
import opt.jobshop.ScheduledOperation;

import java.time.Duration;
import java.util.*;

/** Compiles a job shop model into CP-SAT (intervals + NoOverlap) and solves it. */
public final class CpSatJobShopSolver {

    private final List<String> machines;
    private final List<JobDef> jobs;

    private long timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    public CpSatJobShopSolver(List<String> machines, List<JobDef> jobs) {
        this.machines = List.copyOf(machines);
        this.jobs = List.copyOf(jobs);
    }

    public CpSatJobShopSolver timeLimit(Duration limit) { this.timeLimitSeconds = limit.toSeconds(); return this; }
    public CpSatJobShopSolver workers(int n)            { this.workers = n;                            return this; }
    public CpSatJobShopSolver seed(long s)              { this.seed = s;                               return this; }
    public CpSatJobShopSolver verbose(boolean v)        { this.verbose = v;                            return this; }

    public JobShopSchedule solve() {
        validate();

        // Horizon = sum of all durations (loose upper bound)
        long horizon = jobs.stream()
                .flatMap(j -> j.operations().stream())
                .mapToLong(OperationDef::duration)
                .sum();

        CpModel model = new CpModel();

        // intervals[j][o] = interval variable for job j, operation o
        // For flexible operations, we create one interval per candidate machine
        // and use an "exactly one present" constraint.
        record OpIntervals(IntVar start, IntVar end, IntervalVar interval,
                           // for flexible: per-machine optional intervals + presence literals
                           Map<String, IntervalVar> optionals, Map<String, BoolVar> presences) {}

        List<List<OpIntervals>> allIntervals = new ArrayList<>();

        for (JobDef job : jobs) {
            List<OpIntervals> jobIntervals = new ArrayList<>();
            for (OperationDef op : job.operations()) {
                IntVar start = model.newIntVar(0, horizon, "s_" + job.id() + "_" + op.index());
                IntVar end   = model.newIntVar(0, horizon, "e_" + job.id() + "_" + op.index());

                if (!op.isFlexible()) {
                    IntervalVar interval = model.newIntervalVar(
                            start, model.newConstant(op.duration()), end,
                            "i_" + job.id() + "_" + op.index());
                    jobIntervals.add(new OpIntervals(start, end, interval, Map.of(), Map.of()));
                } else {
                    // Flexible: create optional intervals for each candidate machine
                    Map<String, IntervalVar> optionals = new LinkedHashMap<>();
                    Map<String, BoolVar> presences     = new LinkedHashMap<>();
                    for (String machine : op.machines()) {
                        BoolVar pres = model.newBoolVar("p_" + job.id() + "_" + op.index() + "_" + machine);
                        IntervalVar opt = model.newOptionalIntervalVar(
                                start, model.newConstant(op.duration()), end, pres,
                                "oi_" + job.id() + "_" + op.index() + "_" + machine);
                        optionals.put(machine, opt);
                        presences.put(machine, pres);
                    }
                    // Exactly one machine must be chosen
                    model.addExactlyOne(presences.values().toArray(new BoolVar[0]));
                    jobIntervals.add(new OpIntervals(start, end, null, optionals, presences));
                }
            }
            allIntervals.add(jobIntervals);
        }

        // Precedence constraints within each job
        for (int j = 0; j < jobs.size(); j++) {
            List<OpIntervals> jobOps = allIntervals.get(j);
            for (int o = 0; o < jobOps.size() - 1; o++) {
                model.addLessOrEqual(jobOps.get(o).end(), jobOps.get(o + 1).start());
            }
        }

        // NoOverlap per machine
        for (String machine : machines) {
            List<IntervalVar> machineIntervals = new ArrayList<>();
            for (int j = 0; j < jobs.size(); j++) {
                List<OperationDef> ops = jobs.get(j).operations();
                List<OpIntervals> intervals = allIntervals.get(j);
                for (int o = 0; o < ops.size(); o++) {
                    OperationDef op = ops.get(o);
                    OpIntervals iv = intervals.get(o);
                    if (!op.isFlexible() && op.machine().equals(machine)) {
                        machineIntervals.add(iv.interval());
                    } else if (op.isFlexible() && op.machines().contains(machine)) {
                        machineIntervals.add(iv.optionals().get(machine));
                    }
                }
            }
            if (!machineIntervals.isEmpty()) {
                model.addNoOverlap(machineIntervals.toArray(new IntervalVar[0]));
            }
        }

        // Makespan: max end across all last operations in each job
        IntVar makespan = model.newIntVar(0, horizon, "makespan");
        for (int j = 0; j < jobs.size(); j++) {
            List<OpIntervals> jobOps = allIntervals.get(j);
            model.addLessOrEqual(jobOps.get(jobOps.size() - 1).end(), makespan);
        }
        model.minimize(makespan);

        // Solve
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(workers);
        solver.getParameters().setRandomSeed((int) seed);
        solver.getParameters().setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        CpSolverStatus cpStatus = solver.solve(model);
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        SolveStatus status = mapStatus(cpStatus);
        long makespanValue = 0;
        List<ScheduledOperation> operations = new ArrayList<>();

        if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
            makespanValue = solver.value(makespan);
            for (int j = 0; j < jobs.size(); j++) {
                JobDef job = jobs.get(j);
                List<OperationDef> ops = job.operations();
                List<OpIntervals> intervals = allIntervals.get(j);
                for (int o = 0; o < ops.size(); o++) {
                    OperationDef op = ops.get(o);
                    OpIntervals iv = intervals.get(o);
                    long start = solver.value(iv.start());
                    long end   = solver.value(iv.end());
                    String assignedMachine;
                    if (op.isFlexible()) {
                        assignedMachine = op.machines().stream()
                                .filter(m -> solver.booleanValue(iv.presences().get(m)))
                                .findFirst()
                                .orElseThrow();
                    } else {
                        assignedMachine = op.machine();
                    }
                    operations.add(new ScheduledOperation(job.id(), o, assignedMachine, start, end));
                }
            }
        }

        OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                ? OptionalDouble.of(makespanValue)
                : OptionalDouble.empty();

        return new JobShopSchedule(status, objValue, runtime, makespanValue, machines, operations);
    }

    private void validate() {
        if (jobs.isEmpty()) throw new ModelException("At least one job is required.");
        Set<String> machineSet = new HashSet<>(machines);
        for (JobDef job : jobs) {
            if (job.operations().isEmpty()) {
                throw new ModelException("Job '" + job.id() + "' has no operations.");
            }
            for (OperationDef op : job.operations()) {
                for (String m : op.machines()) {
                    if (!machineSet.contains(m)) {
                        throw new ModelException(
                                "Job '" + job.id() + "' operation " + op.index()
                                        + " references unknown machine '" + m + "'.");
                    }
                }
            }
        }
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

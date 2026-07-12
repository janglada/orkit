package opt.jobshop.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.jobshop.JobShopGoal;
import opt.jobshop.JobShopSchedule;
import opt.jobshop.ScheduledOperation;

import java.time.Duration;
import java.util.*;

/** Compiles a job shop model into CP-SAT and solves it, with optional sequence-dependent setup times. */
public final class CpSatJobShopSolver {

    private final List<String> machines;
    private final List<JobDef> jobs;
    private final SetupMatrix setupMatrix;
    private final JobShopGoal goal;

    private double timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    /** Interval variables built for one operation. */
    private record OpIntervals(
            IntVar start,
            IntVar end,
            /** Fixed-machine interval (non-flexible ops); {@code null} for flexible ops. */
            IntervalVar interval,
            /** Flexible: per-candidate-machine optional intervals. */
            Map<String, IntervalVar> optionals,
            /** Flexible: per-candidate-machine presence literals. */
            Map<String, BoolVar> presences,
            /** Soft tardiness variable; {@code null} when the op has no due date. */
            IntVar tardiness) {}

    /** Creates a solver for the given machines, jobs, setup matrix, and objective. Internal; obtain via {@link opt.jobshop.JobShopBuilder#solver()}. */
    public CpSatJobShopSolver(List<String> machines, List<JobDef> jobs, SetupMatrix setupMatrix, JobShopGoal goal) {
        this.machines = List.copyOf(machines);
        this.jobs = List.copyOf(jobs);
        this.setupMatrix = (setupMatrix == null) ? SetupMatrix.empty() : setupMatrix;
        this.goal = (goal == null) ? new JobShopGoal.MinimizeMakespan() : goal;
    }

    /** Sets the maximum time the CP-SAT solver may run. */
    public CpSatJobShopSolver timeLimit(Duration limit) {
        if (limit == null) throw new ModelException("timeLimit must not be null.");
        this.timeLimitSeconds = limit.toMillis() / 1000.0;
        return this;
    }
    /** Sets the number of parallel search workers. */
    public CpSatJobShopSolver workers(int n)            { this.workers = n;                            return this; }
    /** Sets the random seed for the CP-SAT search. */
    public CpSatJobShopSolver seed(long s)              { this.seed = s;                               return this; }
    /** Enables or disables CP-SAT search-progress logging. */
    public CpSatJobShopSolver verbose(boolean v)        { this.verbose = v;                            return this; }

    /**
     * Builds the CP-SAT model (interval variables, per-machine sequencing via
     * {@code NoOverlap} or a setup-aware circuit, intra-job precedence, and a
     * minimized makespan) and returns the solved {@link JobShopSchedule}.
     */
    public JobShopSchedule solve() {
        validate();

        long totalDuration = jobs.stream()
                .flatMap(j -> j.operations().stream())
                .mapToLong(OperationDef::duration)
                .sum();
        long numOps = jobs.stream().mapToLong(j -> j.operations().size()).sum();
        // Each operation has at most one predecessor, so total setup slack <= numOps * maxSetup.
        long maxSetup = setupMatrix.isEmpty() ? 0 : setupMatrix.maxSetupValue();
        long computedHorizon = totalDuration + numOps * maxSetup;
        // A release date can push a job's completion past the unconstrained horizon
        // (computedHorizon already sums every job's duration, so it dominates any
        // single job's duration + setup slack; adding the largest release upper-bounds
        // the latest a released job can finish). A hard deadline may also exceed it.
        long maxRelease = jobs.stream()
                .flatMap(j -> j.operations().stream())
                .mapToLong(op -> op.releaseTime() == null ? 0 : op.releaseTime())
                .max()
                .orElse(0);
        long maxDeadline = jobs.stream()
                .flatMap(j -> j.operations().stream())
                .mapToLong(op -> op.deadline() == null ? 0 : op.deadline())
                .max()
                .orElse(0);
        long horizon = Math.max(computedHorizon + maxRelease, maxDeadline);

        CpModel model = new CpModel();

        List<List<OpIntervals>> allIntervals = new ArrayList<>();
        // Tardiness variables for operations carrying a soft due date (see objective dispatch).
        List<IntVar> tardinessVars = new ArrayList<>();

        for (JobDef job : jobs) {
            List<OpIntervals> jobIntervals = new ArrayList<>();
            for (OperationDef op : job.operations()) {
                // Hard time window: narrow the start/end domains directly (the
                // booking trick) — release bounds start below, deadline bounds end
                // above. Flexible ops share the same start/end across candidates,
                // so the window applies without presence gating.
                long startLb = (op.releaseTime() != null) ? op.releaseTime() : 0L;
                long endUb   = (op.deadline()   != null) ? op.deadline()   : horizon;
                long startUb = Math.max(startLb, endUb - op.duration());
                IntVar start = model.newIntVar(startLb, startUb, "s_" + job.id() + "_" + op.index());
                IntVar end   = model.newIntVar(0, endUb, "e_" + job.id() + "_" + op.index());

                IntVar tardiness = null;
                if (op.dueTime() != null) {
                    tardiness = model.newIntVar(0, horizon, "tard_" + job.id() + "_" + op.index());
                    // tardiness >= end - due  (and >= 0 from the domain); minimizing
                    // tardiness drives it to max(0, end - due).
                    model.addGreaterOrEqual(tardiness, LinearExpr.affine(end, 1, -op.dueTime()));
                    tardinessVars.add(tardiness);
                }

                if (!op.isFlexible()) {
                    IntervalVar interval = model.newIntervalVar(
                            start, model.newConstant(op.duration()), end,
                            "i_" + job.id() + "_" + op.index());
                    jobIntervals.add(new OpIntervals(start, end, interval, Map.of(), Map.of(), tardiness));
                } else {
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
                    model.addExactlyOne(presences.values().toArray(new BoolVar[0]));
                    jobIntervals.add(new OpIntervals(start, end, null, optionals, presences, tardiness));
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

        // Per-machine sequencing: NoOverlap by default, or a setup-aware circuit
        // when the machine has any nonzero changeover (including initial state).
        for (String machine : machines) {
            List<int[]> slots = new ArrayList<>(); // each entry: {jobIdx, opIdx}
            for (int j = 0; j < jobs.size(); j++) {
                List<OperationDef> ops = jobs.get(j).operations();
                for (int o = 0; o < ops.size(); o++) {
                    OperationDef op = ops.get(o);
                    if (!op.isFlexible() && op.machine().equals(machine)) {
                        slots.add(new int[]{j, o});
                    } else if (op.isFlexible() && op.machines().contains(machine)) {
                        slots.add(new int[]{j, o});
                    }
                }
            }
            if (slots.isEmpty()) continue;

            if (setupMatrix.isEmpty() || !machineHasSetup(machine, slots)) {
                addNoOverlap(model, machine, slots, allIntervals);
            } else {
                addSetupCircuit(model, machine, slots, allIntervals);
            }
        }

        // Makespan: max end across all last operations in each job (always defined;
        // it is part of the objective only for makespan-including goals).
        IntVar makespan = model.newIntVar(0, horizon, "makespan");
        for (int j = 0; j < jobs.size(); j++) {
            List<OpIntervals> jobOps = allIntervals.get(j);
            model.addLessOrEqual(jobOps.get(jobOps.size() - 1).end(), makespan);
        }

        // Objective dispatch. Hard release/deadline constraints are already encoded
        // above; the goal selects what is minimized. Soft due dates take effect only
        // under a goal that includes tardiness.
        LinearExprBuilder obj = LinearExpr.newBuilder();
        switch (goal) {
            case JobShopGoal.MinimizeMakespan ignored ->
                    obj.addTerm(makespan, 1);
            case JobShopGoal.MinimizeTardiness t -> {
                for (IntVar tard : tardinessVars) obj.addTerm(tard, t.weight());
            }
            case JobShopGoal.MinimizeMakespanAndTardiness mt -> {
                obj.addTerm(makespan, mt.makespanWeight());
                for (IntVar tard : tardinessVars) obj.addTerm(tard, mt.tardinessWeight());
            }
            case null, default -> throw new ModelException("Unsupported job-shop goal: " + goal);
        }
        model.minimize(obj.build());

        // Solve
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(workers);
        solver.getParameters().setRandomSeed((int) seed);
        solver.getParameters().setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        CpSolverStatus cpStatus = solver.solve(model);
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        SolveStatus status = SolveStatus.mapStatus(cpStatus);
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
                ? OptionalDouble.of(solver.objectiveValue())
                : OptionalDouble.empty();

        return new JobShopSchedule(status, objValue, runtime, makespanValue, machines, operations);
    }

    /** Adds a plain {@code NoOverlap} over this machine's intervals (fixed + optional). */
    private void addNoOverlap(CpModel model, String machine, List<int[]> slots,
                              List<List<OpIntervals>> allIntervals) {
        List<IntervalVar> machineIntervals = new ArrayList<>();
        for (int[] slot : slots) {
            OperationDef op = jobs.get(slot[0]).operations().get(slot[1]);
            OpIntervals iv = allIntervals.get(slot[0]).get(slot[1]);
            if (!op.isFlexible()) {
                machineIntervals.add(iv.interval());
            } else {
                machineIntervals.add(iv.optionals().get(machine));
            }
        }
        model.addNoOverlap(machineIntervals.toArray(new IntervalVar[0]));
    }

    /** {@code true} if any resolved setup on this machine (incl. initial state) is nonzero. */
    private boolean machineHasSetup(String machine, List<int[]> slots) {
        String initial = setupMatrix.initialType(machine);
        for (int a = 0; a < slots.size(); a++) {
            OperationDef opA = opAt(slots.get(a));
            if (setupMatrix.resolve(machine, initial, opA.type()) > 0) return true;
            for (int b = 0; b < slots.size(); b++) {
                if (a == b) continue;
                OperationDef opB = opAt(slots.get(b));
                if (setupMatrix.resolve(machine, opA.type(), opB.type()) > 0) return true;
            }
        }
        return false;
    }

    /**
     * Builds a circuit over the operations on {@code machine} plus a dummy start
     * node. Each arc {@code i -> j} enforces {@code end_i + setup(i,j) <= start_j},
     * so adjacency both sequences and gaps the operations. Flexible operations are
     * optional nodes (absent via a self-loop on the negated machine-presence literal).
     */
    private void addSetupCircuit(CpModel model, String machine, List<int[]> slots,
                                 List<List<OpIntervals>> allIntervals) {
        int k = slots.size();
        int dummy = k; // always-present start node encoding initial state
        String initial = setupMatrix.initialType(machine);

        CircuitConstraint circuit = model.addCircuit();

        // Self-loops for flexible (optional) nodes: node i is absent when not on this machine.
        for (int i = 0; i < k; i++) {
            OperationDef op = opAt(slots.get(i));
            if (op.isFlexible()) {
                BoolVar presence = allIntervals.get(slots.get(i)[0]).get(slots.get(i)[1]).presences().get(machine);
                circuit.addArc(i, i, presence.not());
            }
        }

        // Real arcs i -> j enforce the changeover gap (or plain no-overlap when setup is 0).
        for (int i = 0; i < k; i++) {
            OpIntervals ivI = allIntervals.get(slots.get(i)[0]).get(slots.get(i)[1]);
            OperationDef opI = opAt(slots.get(i));
            for (int j = 0; j < k; j++) {
                if (i == j) continue;
                OpIntervals ivJ = allIntervals.get(slots.get(j)[0]).get(slots.get(j)[1]);
                OperationDef opJ = opAt(slots.get(j));
                int setup = setupMatrix.resolve(machine, opI.type(), opJ.type());
                BoolVar arc = model.newBoolVar("arc_" + machine + "_" + i + "_" + j);
                circuit.addArc(i, j, arc);
                // end_i + setup <= start_j  (gated by the arc literal)
                model.addLessOrEqual(LinearExpr.affine(ivI.end(), 1, setup), ivJ.start())
                        .onlyEnforceIf(arc);
            }
        }

        // Dummy -> i: first operation pays setup from the machine's initial type.
        for (int i = 0; i < k; i++) {
            OpIntervals ivI = allIntervals.get(slots.get(i)[0]).get(slots.get(i)[1]);
            OperationDef opI = opAt(slots.get(i));
            int setup = setupMatrix.resolve(machine, initial, opI.type());
            BoolVar arc = model.newBoolVar("arc_" + machine + "_d_" + i);
            circuit.addArc(dummy, i, arc);
            if (setup > 0) {
                model.addGreaterOrEqual(ivI.start(), setup).onlyEnforceIf(arc);
            }
        }

        // i -> dummy: closing arc (no operation follows the last).
        for (int i = 0; i < k; i++) {
            BoolVar arc = model.newBoolVar("arc_" + machine + "_" + i + "_d");
            circuit.addArc(i, dummy, arc);
        }
    }

    private OperationDef opAt(int[] slot) {
        return jobs.get(slot[0]).operations().get(slot[1]);
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
}

package opt.jobshop;

import opt.ModelException;
import opt.CpSatProblem;
import opt.CpSatSolver;
import opt.internal.AbstractCpSatSolver;
import opt.internal.Validate;
import opt.jobshop.internal.CpSatJobShopSolver;
import opt.jobshop.internal.JobDef;
import opt.jobshop.internal.SetupMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for job shop problems. Entry point: {@code Optimize.jobShop()}. */
public final class JobShopBuilder implements CpSatProblem<JobShopSchedule> {

    private final Set<String> machines = new LinkedHashSet<>();
    private final List<JobDef> jobs = new ArrayList<>();
    private final Set<String> jobIds = new LinkedHashSet<>();

    // ── Sequence-dependent setup state ──────────────────────────────────────
    // global: (fromType -> (toType -> setup))
    private final Map<String, Map<String, Integer>> globalSetup = new LinkedHashMap<>();
    // perMachine: (machine -> (fromType -> (toType -> setup)))
    private final Map<String, Map<String, Map<String, Integer>>> perMachineSetup = new LinkedHashMap<>();
    private int defaultSetupTime = 0;
    // initialTypes: (machine -> type)
    private final Map<String, String> initialTypes = new LinkedHashMap<>();

    // Objective — default minimizes makespan; release/deadline/due are set per operation.
    private JobShopGoal goal = new JobShopGoal.MinimizeMakespan();

    /** Registers one or more machines that operations can be scheduled on. */
    public JobShopBuilder machines(String... machineIds) {
        for (String m : machineIds) {
            Validate.nonBlank(m, "machine id");
            machines.add(m);
        }
        return this;
    }

    /**
     * Adds a job to the schedule, configuring its ordered operations inside the
     * supplied lambda via {@link JobConfig}.
     */
    public JobShopBuilder job(String id, Consumer<JobConfig> config) {
        Validate.nonBlank(id, "job id");
        if (!jobIds.add(id)) {
            throw new ModelException("Duplicate job id: '" + id + "'.");
        }
        JobConfig cfg = new JobConfig();
        config.accept(cfg);
        cfg.commitPending();
        if (cfg.committed.isEmpty()) {
            throw new ModelException("Job '" + id + "' must have at least one operation.");
        }
        jobs.add(new JobDef(id, List.copyOf(cfg.committed)));
        return this;
    }

    /**
     * Adds a global sequence-dependent setup time applied on every machine
     * when an operation of {@code fromType} is followed by one of {@code toType}.
     * Asymmetric pairs must be declared separately.
     */
    public JobShopBuilder setupTime(String fromType, String toType, int units) {
        Validate.nonBlank(fromType, "fromType");
        Validate.nonBlank(toType, "toType");
        Validate.nonNegative(units, "setup time");
        globalSetup.computeIfAbsent(fromType, k -> new LinkedHashMap<>()).put(toType, units);
        return this;
    }

    /**
     * Adds a machine-specific setup time override, taking precedence over the
     * global matrix on that machine.
     */
    public JobShopBuilder setupTimeOn(String machine, String fromType, String toType, int units) {
        Validate.nonBlank(machine, "machine");
        Validate.nonBlank(fromType, "fromType");
        Validate.nonBlank(toType, "toType");
        Validate.nonNegative(units, "setup time");
        perMachineSetup
                .computeIfAbsent(machine, k -> new LinkedHashMap<>())
                .computeIfAbsent(fromType, k -> new LinkedHashMap<>())
                .put(toType, units);
        return this;
    }

    /**
     * Sets the default setup time applied to any unlisted transition between
     * two different typed operations. Not applied to untyped or same-type ops.
     */
    public JobShopBuilder defaultSetupTime(int units) {
        Validate.nonNegative(units, "default setup time");
        this.defaultSetupTime = units;
        return this;
    }

    /**
     * Declares the type a machine held before the horizon, so the first
     * operation on that machine pays a setup if its type differs.
     */
    public JobShopBuilder initialTypeOn(String machine, String type) {
        Validate.nonBlank(machine, "machine");
        Validate.nonBlank(type, "type");
        initialTypes.put(machine, type);
        return this;
    }

    /**
     * Sets the optimization objective. The default {@link Goals#minimizeMakespan()}
     * preserves historical behavior. Use {@link Goals#minimizeTardiness(long)} or
     * {@link Goals#minimizeMakespanAndTardiness(long, long)} to penalize soft due
     * dates (set per operation via {@link OperationConfig#dueBy(long)}).
     */
    public JobShopBuilder goal(JobShopGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.goal = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public CpSatProblem<JobShopSchedule> build() {
        SetupMatrix matrix = buildSetupMatrix();
        return new JobShopProblem(List.copyOf(machines), List.copyOf(jobs), matrix, goal);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<JobShopSchedule> solver() {
        return build().solver();
    }

    private SetupMatrix buildSetupMatrix() {
        // Validate machine-specific entries reference declared machines.
        // (machines(...) may be called after setupTimeOn(...), so check here.)
        for (String machine : perMachineSetup.keySet()) {
            if (!machines.contains(machine)) {
                throw new ModelException(
                        "setupTimeOn references unknown machine '" + machine + "'.");
            }
        }
        for (String machine : initialTypes.keySet()) {
            if (!machines.contains(machine)) {
                throw new ModelException(
                        "initialTypeOn references unknown machine '" + machine + "'.");
            }
        }

        SetupMatrix matrix = new SetupMatrix(
                copyMatrix(globalSetup),
                copyPerMachine(perMachineSetup),
                defaultSetupTime,
                Map.copyOf(initialTypes));

        warnOnUnusedTypes(matrix);
        return matrix;
    }

    private void warnOnUnusedTypes(SetupMatrix matrix) {
        // Types used by operations + initial types are "in use".
        Set<String> used = new LinkedHashSet<>(initialTypes.values());
        for (JobDef job : jobs) {
            for (var op : job.operations()) {
                if (op.type() != null) used.add(op.type());
            }
        }
        for (String type : matrix.referencedTypes()) {
            if (!used.contains(type)) {
                System.err.println(
                        "WARNING: setup type '" + type
                                + "' is declared in the setup matrix but not used"
                                + " by any operation or initial state; it has no effect"
                                + " (matrices may be shared across models).");
            }
        }
    }

    private static Map<String, Map<String, Integer>> copyMatrix(
            Map<String, Map<String, Integer>> src) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            out.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }

    private static Map<String, Map<String, Map<String, Integer>>> copyPerMachine(
            Map<String, Map<String, Map<String, Integer>>> src) {
        Map<String, Map<String, Map<String, Integer>>> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            Map<String, Map<String, Integer>> inner = new LinkedHashMap<>();
            for (var e2 : e.getValue().entrySet()) {
                inner.put(e2.getKey(), Map.copyOf(e2.getValue()));
            }
            out.put(e.getKey(), Map.copyOf(inner));
        }
        return Map.copyOf(out);
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class JobShopProblem implements CpSatProblem<JobShopSchedule> {

        private final List<String> machines;
        private final List<JobDef> jobs;
        private final SetupMatrix setupMatrix;
        private final JobShopGoal goal;

        JobShopProblem(List<String> machines, List<JobDef> jobs, SetupMatrix setupMatrix, JobShopGoal goal) {
            this.machines = machines;
            this.jobs = jobs;
            this.setupMatrix = setupMatrix;
            this.goal = goal;
        }

        @Override
        public CpSatSolver<JobShopSchedule> solver() {
            return new JobShopSolver(machines, jobs, setupMatrix, goal);
        }
    }

    private static final class JobShopSolver extends AbstractCpSatSolver<JobShopSchedule> {

        private final List<String> machines;
        private final List<JobDef> jobs;
        private final SetupMatrix setupMatrix;
        private final JobShopGoal goal;

        JobShopSolver(List<String> machines, List<JobDef> jobs, SetupMatrix setupMatrix, JobShopGoal goal) {
            this.machines = machines;
            this.jobs = jobs;
            this.setupMatrix = setupMatrix;
            this.goal = goal;
        }

        @Override
        public JobShopSchedule solve() {
            return new CpSatJobShopSolver(machines, jobs, setupMatrix, goal, params, listener, warmStart).solve();
        }
    }
}

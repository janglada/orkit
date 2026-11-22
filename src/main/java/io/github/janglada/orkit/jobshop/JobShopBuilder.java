package io.github.janglada.orkit.jobshop;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.OptimizationProblem;
import io.github.janglada.orkit.CpSatProblem;
import io.github.janglada.orkit.CpSatSolver;
import io.github.janglada.orkit.internal.AbstractCpSatSolver;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.jobshop.internal.CpSatJobShopSolver;
import io.github.janglada.orkit.jobshop.internal.DowntimeDef;
import io.github.janglada.orkit.jobshop.internal.JobDef;
import io.github.janglada.orkit.jobshop.internal.PrecedenceDef;
import io.github.janglada.orkit.jobshop.internal.SetupMatrix;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
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
    private final List<DowntimeDef> downtimes = new ArrayList<>();
    private final Map<String, Integer> capacities = new LinkedHashMap<>();
    /** Declared precedences; an operation index of -1 means the job's last operation. */
    private final List<PrecedenceDef> precedences = new ArrayList<>();
    private TimeGrid grid;

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
     * Registers a machine that runs up to {@code capacity} operations at the same time, such as
     * a bank of identical ovens. Capacity 1 is the same as {@link #machines(String...)}.
     * Sequence-dependent setup times are not supported on a machine with capacity above 1.
     */
    public JobShopBuilder machine(String id, int capacity) {
        Validate.nonBlank(id, "machine id");
        Validate.atLeastOne(capacity, "machine capacity");
        machines.add(id);
        capacities.put(id, capacity);
        return this;
    }

    /**
     * Anchors model time 0 at {@code origin}, so operations may use {@code LocalDateTime} and
     * {@code Duration} values ({@link OperationConfig#takes(Duration)},
     * {@link OperationConfig#releaseAt(LocalDateTime)} and friends) and the schedule reports
     * wall-clock times. One model unit is one minute unless {@link #timeUnit(Duration)} says
     * otherwise. Must be called before any job is declared.
     */
    public JobShopBuilder startingAt(LocalDateTime origin) {
        if (origin == null) throw new ModelException("startingAt() must not be null.");
        requireNoJobs("startingAt()");
        this.grid = new TimeGrid(origin, grid == null ? Duration.ofMinutes(1) : grid.unit());
        return this;
    }

    /**
     * Sets the length of one model time unit for calendar inputs; default one minute. Values
     * that are not whole multiples of it are rejected. Requires
     * {@link #startingAt(LocalDateTime)} and must be called before any job is declared.
     */
    public JobShopBuilder timeUnit(Duration unit) {
        Validate.positive(unit, "timeUnit");
        requireNoJobs("timeUnit()");
        if (grid == null) throw new ModelException("timeUnit() needs startingAt(...) first.");
        this.grid = new TimeGrid(grid.origin(), unit);
        return this;
    }

    private void requireNoJobs(String what) {
        if (!jobs.isEmpty()) throw new ModelException(what + " must be called before any job is declared.");
    }

    /**
     * Requires job {@code beforeJob} to finish before job {@code afterJob} starts: the last
     * operation of the first ends before the first operation of the second starts. Jobs may be
     * declared later; cycles are rejected when the problem is built.
     */
    public JobShopBuilder precedence(String beforeJob, String afterJob) {
        Validate.nonBlank(beforeJob, "precedence job");
        Validate.nonBlank(afterJob, "precedence job");
        precedences.add(new PrecedenceDef(beforeJob, -1, afterJob, 0));
        return this;
    }

    /**
     * Requires operation {@code beforeOp} of {@code beforeJob} to end before operation
     * {@code afterOp} of {@code afterJob} starts (0-based indices). Cycles are rejected when the
     * problem is built.
     */
    public JobShopBuilder precedence(String beforeJob, int beforeOp, String afterJob, int afterOp) {
        Validate.nonBlank(beforeJob, "precedence job");
        Validate.nonBlank(afterJob, "precedence job");
        Validate.nonNegative(beforeOp, "precedence operation index");
        Validate.nonNegative(afterOp, "precedence operation index");
        precedences.add(new PrecedenceDef(beforeJob, beforeOp, afterJob, afterOp));
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
        JobConfig cfg = new JobConfig(grid);
        config.accept(cfg);
        cfg.commitPending();
        if (cfg.committed.isEmpty()) {
            throw new ModelException("Job '" + id + "' must have at least one operation.");
        }
        jobs.add(new JobDef(id, List.copyOf(cfg.committed), cfg.weight));
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
     * Declares that {@code machine} is unavailable during {@code [from, to)} (maintenance, a
     * shift break). Repeatable; overlapping windows on one machine are merged. Operations are
     * non-preemptive, so none may straddle a downtime window.
     */
    public JobShopBuilder downtime(String machine, long from, long to) {
        Validate.nonBlank(machine, "machine");
        Validate.nonNegative(from, "downtime from");
        if (from >= to) {
            throw new ModelException("downtime('" + machine + "', " + from + ", " + to + "): from must be < to.");
        }
        downtimes.add(new DowntimeDef(machine, from, to));
        return this;
    }

    /** Machine downtime as wall-clock times; requires {@link #startingAt(LocalDateTime)}. See {@link #downtime(String, long, long)}. */
    public JobShopBuilder downtime(String machine, LocalDateTime from, LocalDateTime to) {
        if (grid == null) {
            throw new ModelException("downtime(LocalDateTime) needs a calendar: call startingAt(...) first.");
        }
        return downtime(machine, grid.units(from, "downtime from"), grid.units(to, "downtime to"));
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
        for (String m : capacities.keySet()) {
            if (capacities.get(m) > 1 && (perMachineSetup.containsKey(m) || initialTypes.containsKey(m))) {
                throw new ModelException("Machine '" + m + "' has capacity " + capacities.get(m)
                        + "; sequence-dependent setup times need a machine with capacity 1.");
            }
        }
        Model model = new Model(List.copyOf(machines), List.copyOf(jobs), matrix, goal, mergedDowntimes(),
                Map.copyOf(capacities), resolvePrecedences(), grid);
        return new JobShopProblem(model, collectUnusedTypeWarnings(matrix));
    }

    /** Diagnostics for the model as currently declared; see {@link OptimizationProblem#warnings()}. */
    @Override
    public List<String> warnings() {
        return build().warnings();
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

        return matrix;
    }

    /** Resolves job ids and operation indices and rejects precedence cycles. */
    private List<PrecedenceDef> resolvePrecedences() {
        Map<String, JobDef> byId = new LinkedHashMap<>();
        for (JobDef j : jobs) byId.put(j.id(), j);
        List<PrecedenceDef> out = new ArrayList<>();
        for (PrecedenceDef p : precedences) {
            JobDef before = byId.get(p.beforeJob());
            JobDef after = byId.get(p.afterJob());
            if (before == null || after == null) {
                throw new ModelException("precedence('" + p.beforeJob() + "', '" + p.afterJob() + "'): unknown job id '"
                        + (before == null ? p.beforeJob() : p.afterJob()) + "'.");
            }
            int beforeOp = p.beforeOp() < 0 ? before.operations().size() - 1 : p.beforeOp();
            if (beforeOp >= before.operations().size() || p.afterOp() >= after.operations().size()) {
                throw new ModelException("precedence('" + p.beforeJob() + "', " + beforeOp + ", '" + p.afterJob() + "', "
                        + p.afterOp() + "): operation index out of range.");
            }
            out.add(new PrecedenceDef(p.beforeJob(), beforeOp, p.afterJob(), p.afterOp()));
        }
        rejectCycles(byId, out);
        return List.copyOf(out);
    }

    /** Depth-first search over operations, linked within jobs and by precedences. */
    private static void rejectCycles(Map<String, JobDef> byId, List<PrecedenceDef> precedences) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (JobDef j : byId.values()) {
            for (int o = 0; o < j.operations().size(); o++) {
                List<String> out = next.computeIfAbsent(j.id() + "#" + o, k -> new ArrayList<>());
                if (o + 1 < j.operations().size()) out.add(j.id() + "#" + (o + 1));
            }
        }
        for (PrecedenceDef p : precedences) next.get(p.beforeJob() + "#" + p.beforeOp()).add(p.afterJob() + "#" + p.afterOp());
        Map<String, Integer> state = new LinkedHashMap<>(); // 1 = on the stack, 2 = done
        for (String start : next.keySet()) {
            if (state.containsKey(start)) continue;
            Deque<Map.Entry<String, Iterator<String>>> stack = new ArrayDeque<>();
            stack.push(Map.entry(start, next.get(start).iterator()));
            state.put(start, 1);
            while (!stack.isEmpty()) {
                var top = stack.peek();
                if (top.getValue().hasNext()) {
                    String n = top.getValue().next();
                    Integer st = state.get(n);
                    if (st != null && st == 1) {
                        throw new ModelException("precedence: the precedences form a cycle through operation '" + n + "'.");
                    }
                    if (st == null) {
                        state.put(n, 1);
                        stack.push(Map.entry(n, next.get(n).iterator()));
                    }
                } else {
                    state.put(top.getKey(), 2);
                    stack.pop();
                }
            }
        }
    }

    /** Validates downtime machines and merges overlapping or touching windows per machine. */
    private List<DowntimeDef> mergedDowntimes() {
        Map<String, List<DowntimeDef>> byMachine = new LinkedHashMap<>();
        for (DowntimeDef d : downtimes) {
            if (!machines.contains(d.machine())) {
                throw new ModelException("downtime references unknown machine '" + d.machine() + "'.");
            }
            byMachine.computeIfAbsent(d.machine(), k -> new ArrayList<>()).add(d);
        }
        List<DowntimeDef> out = new ArrayList<>();
        for (var e : byMachine.entrySet()) {
            List<DowntimeDef> sorted = new ArrayList<>(e.getValue());
            sorted.sort(java.util.Comparator.comparingLong(DowntimeDef::from));
            DowntimeDef current = null;
            for (DowntimeDef d : sorted) {
                if (current != null && d.from() <= current.to()) {
                    current = new DowntimeDef(current.machine(), current.from(), Math.max(current.to(), d.to()));
                } else {
                    if (current != null) out.add(current);
                    current = d;
                }
            }
            if (current != null) out.add(current);
        }
        return List.copyOf(out);
    }

    private List<String> collectUnusedTypeWarnings(SetupMatrix matrix) {
        // Types used by operations + initial types are "in use".
        Set<String> used = new LinkedHashSet<>(initialTypes.values());
        for (JobDef job : jobs) {
            for (var op : job.operations()) {
                if (op.type() != null) used.add(op.type());
            }
        }
        List<String> warnings = new ArrayList<>();
        for (String type : matrix.referencedTypes()) {
            if (!used.contains(type)) {
                warnings.add("Setup type '" + type
                        + "' is declared in the setup matrix but not used by any operation"
                        + " or initial state; it has no effect (matrices may be shared across models).");
            }
        }
        return List.copyOf(warnings);
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

    /** Everything a solve needs; immutable. */
    private record Model(List<String> machines, List<JobDef> jobs, SetupMatrix setupMatrix, JobShopGoal goal,
                         List<DowntimeDef> downtimes, Map<String, Integer> capacities,
                         List<PrecedenceDef> precedences, TimeGrid grid) {}

    private static final class JobShopProblem implements CpSatProblem<JobShopSchedule> {

        private final Model model;
        private final List<String> warnings;

        JobShopProblem(Model model, List<String> warnings) {
            this.model = model;
            this.warnings = warnings;
        }

        @Override
        public List<String> warnings() {
            return warnings;
        }

        @Override
        public CpSatSolver<JobShopSchedule> solver() {
            return new JobShopSolver(model);
        }
    }

    private static final class JobShopSolver extends AbstractCpSatSolver<JobShopSchedule> {

        private final Model model;

        JobShopSolver(Model model) {
            this.model = model;
        }

        @Override
        protected JobShopSchedule doSolve() {
            TimeGrid grid = model.grid();
            return new CpSatJobShopSolver(model.machines(), model.jobs(), model.setupMatrix(), model.goal(),
                    model.downtimes(), model.capacities(), model.precedences(),
                    grid == null ? null : grid.origin(), grid == null ? null : grid.unit(),
                    params, listener, warmStart).solve();
        }
    }
}

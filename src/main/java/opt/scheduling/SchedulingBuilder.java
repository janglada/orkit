package opt.scheduling;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.scheduling.internal.CpSatSchedulingSolver;
import opt.scheduling.internal.EmployeeDef;
import opt.scheduling.internal.ShiftDef;

import java.time.Duration;
import java.util.Set;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fluent builder for employee scheduling problems. Entry point: {@code Optimize.scheduling()}. */
public final class SchedulingBuilder implements OptimizationProblem<EmployeeRoster> {

    private LocalDate horizonStart;
    private int horizonDays;
    private final Map<String, EmployeeDef> employees = new LinkedHashMap<>();
    private final Map<String, ShiftDef> shifts = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<SoftGoal> softGoals = new ArrayList<>();
    private ObjectiveGoal objective;

    public SchedulingBuilder horizon(LocalDate startDate, int days) {
        this.horizonStart = startDate;
        this.horizonDays = days;
        return this;
    }

    public SchedulingBuilder employee(String id, Consumer<EmployeeConfig> config) {
        if (employees.containsKey(id)) {
            throw new ModelException("Duplicate employee id: " + id);
        }
        EmployeeConfig cfg = new EmployeeConfig();
        cfg.id = id;
        config.accept(cfg);
        employees.put(id, new EmployeeDef(
                id,
                Set.copyOf(cfg.skills),
                cfg.costPerHour,
                cfg.availableDays.isEmpty() ? Set.of() : Set.copyOf(cfg.availableDays),
                cfg.maxHoursPerWeek,
                Set.copyOf(cfg.preferredShifts),
                Set.copyOf(cfg.blockedDates)));
        return this;
    }

    public SchedulingBuilder shift(String id, Consumer<ShiftConfig> config) {
        if (shifts.containsKey(id)) {
            throw new ModelException("Duplicate shift id: " + id);
        }
        ShiftConfig cfg = new ShiftConfig();
        cfg.id = id;
        config.accept(cfg);
        if (cfg.day == null && cfg.specificDate == null)
            throw new ModelException("Shift '" + id + "' is missing .on(DayOfWeek) or .on(LocalDate)");
        if (cfg.day != null && cfg.specificDate != null)
            throw new ModelException("Shift '" + id + "' must specify either .on(DayOfWeek) or .on(LocalDate), not both");
        if (cfg.from == null) throw new ModelException("Shift '" + id + "' is missing .from(LocalTime)");
        if (cfg.to == null)   throw new ModelException("Shift '" + id + "' is missing .to(LocalTime)");
        shifts.put(id, new ShiftDef(id, cfg.day, cfg.specificDate, cfg.from, cfg.to, cfg.needsCount, cfg.requiredSkill));
        return this;
    }

    public SchedulingBuilder constrain(Rule rule) {
        rules.add(rule);
        return this;
    }

    public SchedulingBuilder prefer(SoftGoal goal) {
        softGoals.add(goal);
        return this;
    }

    public SchedulingBuilder minimize(ObjectiveGoal goal) {
        this.objective = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance without running the solver. */
    public OptimizationProblem<EmployeeRoster> build() {
        validateHorizon();
        return new SchedulingProblem(snapshot());
    }

    @Override
    public Solver<EmployeeRoster> solver() {
        return build().solver();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void validateHorizon() {
        if (horizonStart == null || horizonDays <= 0) {
            throw new ModelException("horizon(startDate, days) must be called with days > 0.");
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(
                List.copyOf(employees.values()),
                List.copyOf(shifts.values()),
                horizonStart,
                horizonDays,
                List.copyOf(rules),
                List.copyOf(softGoals),
                objective);
    }

    // ── Immutable problem ────────────────────────────────────────────────────

    private record Snapshot(
            List<EmployeeDef> employees,
            List<ShiftDef> shifts,
            LocalDate horizonStart,
            int horizonDays,
            List<Rule> rules,
            List<SoftGoal> softGoals,
            ObjectiveGoal objective
    ) {}

    private static final class SchedulingProblem implements OptimizationProblem<EmployeeRoster> {

        private final Snapshot snap;

        SchedulingProblem(Snapshot snap) {
            this.snap = snap;
        }

        @Override
        public Solver<EmployeeRoster> solver() {
            return new SchedulingSolver(snap);
        }
    }

    private static final class SchedulingSolver implements Solver<EmployeeRoster> {

        private final Snapshot snap;
        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        SchedulingSolver(Snapshot snap) {
            this.snap = snap;
        }

        @Override
        public Solver<EmployeeRoster> timeLimit(Duration limit) {
            this.timeLimit = limit;
            return this;
        }

        @Override
        public Solver<EmployeeRoster> workers(int threads) {
            this.workers = threads;
            return this;
        }

        @Override
        public Solver<EmployeeRoster> seed(long randomSeed) {
            this.seed = randomSeed;
            return this;
        }

        @Override
        public Solver<EmployeeRoster> verbose(boolean logToStdout) {
            this.verbose = logToStdout;
            return this;
        }

        @Override
        public EmployeeRoster solve() {
            CpSatSchedulingSolver cpSat = new CpSatSchedulingSolver(
                    snap.employees(), snap.shifts(),
                    snap.horizonStart(), snap.horizonDays(),
                    snap.rules(), snap.softGoals(), snap.objective());
            cpSat.timeLimit(timeLimit).workers(workers).seed(seed).verbose(verbose);

            CpSatSchedulingSolver.EmployeeRosterResult result = cpSat.solve();
            return new EmployeeRoster(
                    result.status(), result.objective(), result.runtime(), result.assignments());
        }
    }
}

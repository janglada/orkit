package opt.scheduling;

import opt.ModelException;
import opt.CpSatProblem;
import opt.CpSatSolver;
import opt.internal.AbstractCpSatSolver;
import opt.internal.Validate;
import opt.scheduling.internal.CpSatSchedulingSolver;
import opt.scheduling.internal.EmployeeDef;
import opt.scheduling.internal.ShiftDef;

import java.util.Set;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fluent builder for employee scheduling problems. Entry point: {@code Optimize.scheduling()}. */
public final class SchedulingBuilder implements CpSatProblem<EmployeeRoster> {

    private LocalDate horizonStart;
    private int horizonDays;
    private final Map<String, EmployeeDef> employees = new LinkedHashMap<>();
    private final Map<String, ShiftDef> shifts = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<SoftGoal> softGoals = new ArrayList<>();
    private SchedulingGoal objective;

    /** Sets the scheduling horizon to {@code days} consecutive days starting from {@code startDate}. */
    public SchedulingBuilder horizon(LocalDate startDate, int days) {
        this.horizonStart = startDate;
        this.horizonDays = days;
        return this;
    }

    /** Declares an employee with the given unique id and configures it via the {@link EmployeeConfig} lambda. */
    public SchedulingBuilder employee(String id, Consumer<EmployeeConfig> config) {
        Validate.nonBlank(id, "employee id");
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

    /** Declares a shift with the given unique id and configures it via the {@link ShiftConfig} lambda. */
    public SchedulingBuilder shift(String id, Consumer<ShiftConfig> config) {
        Validate.nonBlank(id, "shift id");
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

    /** Adds a hard {@link Rule} constraint to the model. */
    public SchedulingBuilder constrain(Rule rule) {
        rules.add(rule);
        return this;
    }

    /** Adds a {@link SoftGoal} that contributes a penalty or reward to the weighted objective. */
    public SchedulingBuilder prefer(SoftGoal goal) {
        softGoals.add(goal);
        return this;
    }

    /** Sets the primary {@link SchedulingGoal} the solver optimizes. */
    public SchedulingBuilder goal(SchedulingGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.objective = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance without running the solver. */
    public CpSatProblem<EmployeeRoster> build() {
        validateHorizon();
        return new SchedulingProblem(snapshot());
    }

    /** Convenience method: builds the problem and immediately returns its {@link Solver}. */
    @Override
    public CpSatSolver<EmployeeRoster> solver() {
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
            SchedulingGoal objective
    ) {}

    private static final class SchedulingProblem implements CpSatProblem<EmployeeRoster> {

        private final Snapshot snap;

        SchedulingProblem(Snapshot snap) {
            this.snap = snap;
        }

        @Override
        public CpSatSolver<EmployeeRoster> solver() {
            return new SchedulingSolver(snap);
        }
    }

    private static final class SchedulingSolver extends AbstractCpSatSolver<EmployeeRoster> {

        private final Snapshot snap;

        SchedulingSolver(Snapshot snap) {
            this.snap = snap;
        }

        /** Runs the configured CP-SAT solver and returns the resulting {@link EmployeeRoster}. */
        @Override
        public EmployeeRoster solve() {
            return new CpSatSchedulingSolver(
                    snap.employees(), snap.shifts(),
                    snap.horizonStart(), snap.horizonDays(),
                    snap.rules(), snap.softGoals(), snap.objective(),
                    params, listener, warmStart).solve();
        }
    }
}

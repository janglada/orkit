package opt.scheduling;

import opt.ModelException;
import opt.CpSatProblem;
import opt.CpSatSolver;
import opt.internal.AbstractCpSatSolver;
import opt.internal.Validate;
import opt.scheduling.internal.CpSatSchedulingSolver;
import opt.scheduling.internal.EmployeeDef;
import opt.scheduling.internal.LockDef;
import opt.scheduling.internal.ShiftDef;

import java.util.Set;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fluent builder for employee scheduling problems. Entry point: {@code Optimize.scheduling()}. */
public final class SchedulingBuilder implements CpSatProblem<EmployeeRoster> {

    private LocalDate horizonStart;
    private int horizonDays;
    private final Map<String, EmployeeDef> employees = new LinkedHashMap<>();
    private final Map<String, ShiftTemplate> shifts = new LinkedHashMap<>();
    private final List<Lock> locks = new ArrayList<>();
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

    /**
     * Declares a shift with the given unique id and configures it via the {@link ShiftConfig} lambda.
     * A recurring shift ({@code every}/{@code daily}/{@code on(DayOfWeek)}) is expanded into one
     * instance per matching date when the problem is built; all instances report this id.
     */
    public SchedulingBuilder shift(String id, Consumer<ShiftConfig> config) {
        Validate.nonBlank(id, "shift id");
        if (shifts.containsKey(id)) {
            throw new ModelException("Duplicate shift id: " + id);
        }
        ShiftConfig cfg = new ShiftConfig();
        cfg.id = id;
        config.accept(cfg);
        boolean recurring = cfg.daily || !cfg.days.isEmpty();
        if (!recurring && cfg.specificDate == null)
            throw new ModelException("Shift '" + id + "' is missing .on(LocalDate), .on/.every(DayOfWeek...) or .daily()");
        if (recurring && cfg.specificDate != null)
            throw new ModelException("Shift '" + id + "' must specify either a date or a recurrence, not both");
        if (cfg.from == null) throw new ModelException("Shift '" + id + "' is missing .from(LocalTime)");
        if (cfg.to == null)   throw new ModelException("Shift '" + id + "' is missing .to(LocalTime)");
        int min = cfg.minCount < 0 ? cfg.needsCount : cfg.minCount;
        if (min > cfg.needsCount)
            throw new ModelException("Shift '" + id + "': needsAtLeast(" + min + ") exceeds needs(" + cfg.needsCount + ").");
        shifts.put(id, new ShiftTemplate(id, Set.copyOf(cfg.days), cfg.daily, cfg.specificDate,
                cfg.from, cfg.to, cfg.needsCount, min, cfg.requiredSkill, cfg.category));
        return this;
    }

    /**
     * Forces {@code employeeId} onto every instance of {@code shiftId}. The employee must be
     * eligible (skill, availability) for each instance; validated when the problem is built.
     */
    public SchedulingBuilder assign(String employeeId, String shiftId) {
        return lock(employeeId, shiftId, null, true);
    }

    /** Forces {@code employeeId} onto the instance of {@code shiftId} that falls on {@code date}. */
    public SchedulingBuilder assign(String employeeId, String shiftId, LocalDate date) {
        if (date == null) throw new ModelException("assign(): date must not be null.");
        return lock(employeeId, shiftId, date, true);
    }

    /** Forbids {@code employeeId} on every instance of {@code shiftId}. */
    public SchedulingBuilder forbid(String employeeId, String shiftId) {
        return lock(employeeId, shiftId, null, false);
    }

    /** Forbids {@code employeeId} on the instance of {@code shiftId} that falls on {@code date}. */
    public SchedulingBuilder forbid(String employeeId, String shiftId, LocalDate date) {
        if (date == null) throw new ModelException("forbid(): date must not be null.");
        return lock(employeeId, shiftId, date, false);
    }

    private SchedulingBuilder lock(String employeeId, String shiftId, LocalDate date, boolean assign) {
        Validate.nonBlank(employeeId, "employee id");
        Validate.nonBlank(shiftId, "shift id");
        locks.add(new Lock(employeeId, shiftId, date, assign));
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

    /** Convenience method: builds the problem and immediately returns its {@link CpSatSolver}. */
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
        List<ShiftDef> instances = expandShifts();
        List<LockDef> lockDefs = resolveLocks(instances);
        validateCategories(instances);
        return new Snapshot(
                List.copyOf(employees.values()),
                instances,
                horizonStart,
                horizonDays,
                List.copyOf(rules),
                List.copyOf(softGoals),
                objective,
                lockDefs);
    }

    /** Expands each declared shift into one {@link ShiftDef} per date it occurs on within the horizon. */
    private List<ShiftDef> expandShifts() {
        List<ShiftDef> out = new ArrayList<>();
        for (ShiftTemplate t : shifts.values()) {
            if (t.date() != null) {
                long idx = java.time.temporal.ChronoUnit.DAYS.between(horizonStart, t.date());
                if (idx < 0 || idx >= horizonDays) {
                    throw new ModelException("Shift '" + t.id() + "' date " + t.date()
                            + " is outside the horizon [" + horizonStart + ", +" + horizonDays + " days)");
                }
                out.add(new ShiftDef(t.id(), t.id(), t.date(), t.from(), t.to(), t.needs(), t.min(), t.skill(), t.category()));
                continue;
            }
            int before = out.size();
            for (int d = 0; d < horizonDays; d++) {
                LocalDate date = horizonStart.plusDays(d);
                if (t.daily() || t.days().contains(date.getDayOfWeek())) {
                    out.add(new ShiftDef(t.id() + "@" + date, t.id(), date, t.from(), t.to(),
                            t.needs(), t.min(), t.skill(), t.category()));
                }
            }
            if (out.size() == before) {
                throw new ModelException("Shift '" + t.id() + "' never occurs in the horizon ["
                        + horizonStart + ", +" + horizonDays + " days)");
            }
        }
        return List.copyOf(out);
    }

    /** Resolves assign/forbid declarations to shift instances and checks them for consistency. */
    private List<LockDef> resolveLocks(List<ShiftDef> instances) {
        List<LockDef> out = new ArrayList<>();
        Map<String, Boolean> decided = new LinkedHashMap<>();
        Map<String, Integer> assignedPerInstance = new LinkedHashMap<>();
        for (Lock lock : locks) {
            String verb = lock.assign() ? "assign" : "forbid";
            EmployeeDef emp = employees.get(lock.employeeId());
            if (emp == null) {
                throw new ModelException(verb + "('" + lock.employeeId() + "','" + lock.shiftId()
                        + "'): unknown employee id '" + lock.employeeId() + "'.");
            }
            List<ShiftDef> targets = instances.stream()
                    .filter(s -> s.templateId().equals(lock.shiftId()))
                    .filter(s -> lock.date() == null || s.date().equals(lock.date()))
                    .toList();
            if (targets.isEmpty()) {
                throw new ModelException(verb + "('" + lock.employeeId() + "','" + lock.shiftId() + "'"
                        + (lock.date() == null ? "" : ",'" + lock.date() + "'")
                        + "): unknown shift id '" + lock.shiftId() + "'"
                        + (lock.date() == null ? "." : " on " + lock.date() + "."));
            }
            for (ShiftDef s : targets) {
                String key = lock.employeeId() + "\u0000" + s.id();
                Boolean previous = decided.put(key, lock.assign());
                if (previous != null && previous != lock.assign()) {
                    throw new ModelException("'" + lock.employeeId() + "' is both assigned to and forbidden from '"
                            + s.templateId() + "' on " + s.date() + ".");
                }
                if (lock.assign()) {
                    if (!emp.hasSkill(s.requiredSkill()) || !emp.isAvailableOn(s.date().getDayOfWeek(), s.date())) {
                        throw new ModelException("assign('" + lock.employeeId() + "','" + s.templateId() + "'): employee '"
                                + lock.employeeId() + "' is not eligible for this shift on " + s.date()
                                + " (skill/availability).");
                    }
                    if (previous == null) {
                        int n = assignedPerInstance.merge(s.id(), 1, Integer::sum);
                        if (n > s.needsCount()) {
                            throw new ModelException("Shift '" + s.templateId() + "' on " + s.date() + " has " + n
                                    + " assigned employees but needs " + s.needsCount() + ".");
                        }
                    }
                }
                if (previous == null) out.add(new LockDef(lock.employeeId(), s.id(), lock.assign()));
            }
        }
        return List.copyOf(out);
    }

    /** Every category referenced by a {@link Rule.ForbidSequence} must be declared on some shift. */
    private void validateCategories(List<ShiftDef> instances) {
        Set<String> declared = new LinkedHashSet<>();
        for (ShiftDef s : instances) if (s.category() != null) declared.add(s.category());
        for (Rule rule : rules) {
            if (rule instanceof Rule.ForbidSequence fs) {
                for (String c : List.of(fs.fromCategory(), fs.toCategory())) {
                    if (!declared.contains(c)) {
                        throw new ModelException("forbidSequence: no shift declares category '" + c + "'.");
                    }
                }
            }
        }
    }

    // ── Immutable problem ────────────────────────────────────────────────────

    /** A declared shift before expansion over the horizon. */
    private record ShiftTemplate(String id, Set<DayOfWeek> days, boolean daily, LocalDate date,
                                 java.time.LocalTime from, java.time.LocalTime to,
                                 int needs, int min, String skill, String category) {}

    /** An assign/forbid declaration before resolution; {@code date == null} means every instance. */
    private record Lock(String employeeId, String shiftId, LocalDate date, boolean assign) {}

    private record Snapshot(
            List<EmployeeDef> employees,
            List<ShiftDef> shifts,
            LocalDate horizonStart,
            int horizonDays,
            List<Rule> rules,
            List<SoftGoal> softGoals,
            SchedulingGoal objective,
            List<LockDef> locks
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
                    snap.rules(), snap.softGoals(), snap.objective(), snap.locks(),
                    params, listener, warmStart).solve();
        }
    }
}

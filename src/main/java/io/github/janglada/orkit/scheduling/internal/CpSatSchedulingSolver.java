package io.github.janglada.orkit.scheduling.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.internal.CpSatParams;
import java.util.function.Consumer;
import io.github.janglada.orkit.scheduling.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Compiles a scheduling model description into CP-SAT and runs it.
 * All CP-SAT types are confined to this package.
 */
public final class CpSatSchedulingSolver {

    private final List<EmployeeDef> employees;
    private final List<ShiftDef> shifts;
    private final LocalDate horizonStart;
    private final int horizonDays;
    private final List<Rule> rules;
    private final List<SoftGoal> softGoals;
    private final SchedulingGoal objective;
    private final List<LockDef> locks;
    private final CpSatParams params;
    private final Consumer<EmployeeRoster> listener;
    private final EmployeeRoster warmStart;


    /** Creates a solver bound to the given scheduling model description; inputs are defensively copied. */
    public CpSatSchedulingSolver(List<EmployeeDef> employees,
                                 List<ShiftDef> shifts,
                                 LocalDate horizonStart,
                                 int horizonDays,
                                 List<Rule> rules,
                                 List<SoftGoal> softGoals,
                                 SchedulingGoal objective,
                                 List<LockDef> locks,
                                 CpSatParams params,
                                 Consumer<EmployeeRoster> listener,
                                 EmployeeRoster warmStart) {
        this.employees = List.copyOf(employees);
        this.shifts = List.copyOf(shifts);
        this.horizonStart = horizonStart;
        this.horizonDays = horizonDays;
        this.rules = List.copyOf(rules);
        this.softGoals = List.copyOf(softGoals);
        this.objective = objective;
        this.locks = (locks == null) ? List.of() : List.copyOf(locks);
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }


    /**
     * Builds the CP-SAT model from the configured employees, shifts, rules, soft goals, and
     * objective, then runs the solver to completion or time limit.
     * <p>
     * The model fixes impossible assignments to zero (skill or availability mismatches),
     * enforces each shift's staffing requirement as an equality, applies weekly hour caps and
     * all registered hard {@link Rule}s, and finally minimizes the combined objective (primary
     * {@link SchedulingGoal} plus any {@link SoftGoal} penalties/rewards).
     * @return the solved roster
     */
    public EmployeeRoster solve() {
        validate();

        int E = employees.size();
        int S = shifts.size();

        CpModel model = new CpModel();

        // x[e][s] = 1 if employee e is assigned to shift s
        BoolVar[][] x = new BoolVar[E][S];
        for (int e = 0; e < E; e++) {
            for (int s = 0; s < S; s++) {
                x[e][s] = model.newBoolVar("x_" + e + "_" + s);
            }
        }

        // Each shift instance carries its concrete date; derive the 0-based horizon day-index.
        LocalDate[] shiftDate = new LocalDate[S];
        int[] shiftDayIdx = new int[S];
        for (int s = 0; s < S; s++) {
            ShiftDef shift = shifts.get(s);
            int idx = (int) ChronoUnit.DAYS.between(horizonStart, shift.date());
            if (idx < 0 || idx >= horizonDays) {
                throw new ModelException("Shift '" + shift.templateId() + "' date " + shift.date()
                        + " is outside the horizon [" + horizonStart + ", +" + horizonDays + " days)");
            }
            shiftDayIdx[s] = idx;
            shiftDate[s] = shift.date();
        }

        // Availability and skill: fix impossible assignments to 0.
        for (int e = 0; e < E; e++) {
            EmployeeDef emp = employees.get(e);
            for (int s = 0; s < S; s++) {
                ShiftDef shift = shifts.get(s);
                if (!emp.isAvailableOn(shiftDate[s].getDayOfWeek(), shiftDate[s]) || !emp.hasSkill(shift.requiredSkill())) {
                    model.addEquality(x[e][s], 0L);
                }
            }
        }

        // Locked decisions (assign / forbid). Assigned pairs were validated eligible at build time,
        // so they are also counted in the staffing sums below.
        Map<String, Integer> empIndex = new HashMap<>();
        for (int e = 0; e < E; e++) empIndex.put(employees.get(e).id(), e);
        Map<String, Integer> shiftIndex = new HashMap<>();
        for (int s = 0; s < S; s++) shiftIndex.put(shifts.get(s).id(), s);
        for (LockDef lock : locks) {
            Integer e = empIndex.get(lock.employeeId());
            Integer s = shiftIndex.get(lock.shiftInstanceId());
            if (e == null || s == null) continue;
            model.addEquality(x[e][s], lock.assign() ? 1L : 0L);
        }

        // Staffing requirements: for each shift, sum of qualified employees == needs, or within
        // [min, needs] with a penalized shortfall when the shift is soft-staffed.
        List<IntVar> shortfalls = new ArrayList<>();
        for (int s = 0; s < S; s++) {
            ShiftDef shift = shifts.get(s);
            LinearExprBuilder expr = LinearExpr.newBuilder();
            for (int e = 0; e < E; e++) {
                EmployeeDef emp = employees.get(e);
                if (emp.isAvailableOn(shiftDate[s].getDayOfWeek(), shiftDate[s]) && emp.hasSkill(shift.requiredSkill())) {
                    expr.add(x[e][s]);
                }
            }
            if (!shift.isSoftStaffed()) {
                model.addEquality(expr, shift.needsCount());
            } else {
                IntVar shortfall = model.newIntVar(0, shift.needsCount() - shift.minCount(), "short_" + s);
                model.addLinearConstraint(expr, shift.minCount(), shift.needsCount());
                model.addEquality(LinearExpr.newBuilder().add(expr.build()).add(shortfall).build(), shift.needsCount());
                shortfalls.add(shortfall);
            }
        }

        // Max hours per week: rolling 7-day window (see applyMaxHoursPerWeek)
        applyMaxHoursPerWeek(model, x, E, S, shiftDayIdx);

        // Hard rules
        for (Rule rule : rules) {
            switch (rule) {
                case Rule.OneShiftPerEmployeePerDay r -> applyOneShiftPerDay(model, x, E, S, shiftDayIdx);
                case Rule.MinRestBetweenShifts r      -> applyMinRest(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxConsecutiveWorkDays r    -> applyMaxConsecutiveWorkDays(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxShiftsPerWeek r          -> applyMaxShiftsPerWeek(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxConsecutiveNightShifts r -> applyMaxConsecutiveNightShifts(model, x, E, S, r, shiftDayIdx);
                case Rule.ForbidSequence r            -> applyForbidSequence(model, x, E, S, r, shiftDayIdx);
            }
        }

        // Build objective expression
        LinearExprBuilder objExpr = LinearExpr.newBuilder();
        boolean hasObjective = false;

        if (objective instanceof SchedulingGoal.MinimizeTotalLaborCost) {
            for (int e = 0; e < E; e++) {
                int rate = employees.get(e).costPerHour();
                if (rate > 0) {
                    for (int s = 0; s < S; s++) {
                        long minuteCost = (long) shifts.get(s).durationMinutes() * rate;
                        objExpr.addTerm(x[e][s], minuteCost);
                    }
                }
            }
            hasObjective = true;
        }

        // Soft goals — add penalty/reward terms scaled so they complement the hard objective
        boolean understaffingWeighted = false;
        for (SoftGoal goal : softGoals) {
            switch (goal) {
                case SoftGoal.BalanceWorkloadAcrossEmployees g ->
                        hasObjective |= applyBalanceGoal(model, x, E, S, objExpr, g);
                case SoftGoal.HonorEmployeePreferences g ->
                        hasObjective |= applyPreferencesGoal(model, x, E, S, objExpr, g);
                case SoftGoal.PenalizeUnderstaffing g -> {
                    for (IntVar shortfall : shortfalls) objExpr.addTerm(shortfall, g.weight());
                    understaffingWeighted = true;
                    hasObjective |= !shortfalls.isEmpty();
                }
            }
        }
        // A soft-staffed shift with no declared weight still needs an incentive to fill up,
        // otherwise the range [min, needs] would silently settle at the minimum.
        if (!understaffingWeighted && !shortfalls.isEmpty()) {
            for (IntVar shortfall : shortfalls) objExpr.addTerm(shortfall, SoftGoal.DEFAULT_UNDERSTAFFING_WEIGHT);
            hasObjective = true;
        }

        if (hasObjective) {
            model.minimize(objExpr);
        }

        final boolean objectiveModeled = hasObjective;
        if (warmStart != null) applyWarmStart(model, x);

        CpSatParams.Extractor<EmployeeRoster> extract = (v, status, runtime) -> {
            boolean feasible = status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;

            List<io.github.janglada.orkit.scheduling.ShiftAssignment> assignments = new ArrayList<>();
            if (feasible) {
                for (int e = 0; e < E; e++) {
                    EmployeeDef emp = employees.get(e);
                    for (int s = 0; s < S; s++) {
                        if (v.booleanValue(x[e][s])) {
                            ShiftDef shift = shifts.get(s);
                            LocalDate date = shiftDate[s];
                            assignments.add(new io.github.janglada.orkit.scheduling.ShiftAssignment(
                                    emp.id(), shift.templateId(), date, shift.from(), shift.to()));
                        }
                    }
                }
            }

            OptionalDouble objectiveValue = (objectiveModeled && feasible)
                    ? OptionalDouble.of(v.objectiveValue())
                    : OptionalDouble.empty();

            return new EmployeeRoster(status, objectiveValue, runtime, assignments);
        };
        return params.solve(model, extract, listener);
    }

    /** Hints every (employee, shift) literal from a previous roster: 1 where it was assigned, 0 elsewhere. */
    private void applyWarmStart(CpModel model, BoolVar[][] x) {
        Set<String> assigned = new HashSet<>();
        for (io.github.janglada.orkit.scheduling.ShiftAssignment a : warmStart.assignments()) {
            assigned.add(a.employee() + "\u0000" + a.shift() + "\u0000" + a.date());
        }
        for (int e = 0; e < employees.size(); e++) {
            for (int s = 0; s < shifts.size(); s++) {
                ShiftDef shift = shifts.get(s);
                boolean on = assigned.contains(employees.get(e).id() + "\u0000" + shift.templateId() + "\u0000" + shift.date());
                model.addHint(x[e][s], on ? 1 : 0);
            }
        }
    }

    // ── Soft goal helpers ───────────────────────────────────────────────────

    private boolean applyBalanceGoal(CpModel model, BoolVar[][] x, int E, int S, LinearExprBuilder objExpr,
                                     SoftGoal.BalanceWorkloadAcrossEmployees g) {
        int totalMaxMinutes = shifts.stream().mapToInt(ShiftDef::durationMinutes).sum();
        if (totalMaxMinutes == 0) return false;

        IntVar maxLoad = model.newIntVar(0, totalMaxMinutes, "maxLoad");
        IntVar minLoad = model.newIntVar(0, totalMaxMinutes, "minLoad");

        for (int e = 0; e < E; e++) {
            BoolVar[] vars = new BoolVar[S];
            long[] durations = new long[S];
            for (int s = 0; s < S; s++) {
                vars[s] = x[e][s];
                durations[s] = shifts.get(s).durationMinutes();
            }
            LinearExpr empLoad = buildWeightedSum(vars, durations);
            model.addGreaterOrEqual(maxLoad, empLoad);
            model.addLessOrEqual(minLoad, empLoad);
        }

        IntVar imbalance = model.newIntVar(0, totalMaxMinutes, "imbalance");
        // imbalance = maxLoad - minLoad
        model.addEquality(imbalance,
                LinearExpr.newBuilder().add(maxLoad).addTerm(minLoad, -1L).build());
        objExpr.addTerm(imbalance, g.weight()); // penalize imbalance
        return true;
    }

    private boolean applyPreferencesGoal(CpModel model, BoolVar[][] x, int E, int S, LinearExprBuilder objExpr,
                                         SoftGoal.HonorEmployeePreferences g) {
        boolean added = false;
        for (int e = 0; e < E; e++) {
            Set<String> prefs = employees.get(e).preferredShifts();
            if (prefs.isEmpty()) continue;
            for (int s = 0; s < S; s++) {
                if (prefs.contains(shifts.get(s).templateId())) {
                    // Reward (subtract) when preference is satisfied
                    objExpr.addTerm(x[e][s], -g.weight());
                    added = true;
                }
            }
        }
        return added;
    }

    // ── Rule implementations ────────────────────────────────────────────────

    /**
     * Caps each employee's scheduled minutes in every window of 7 consecutive horizon
     * days at {@code maxHoursPerWeek * 60}. On a horizon of 7 days or fewer this is a
     * single window over the whole horizon; on longer horizons the cap applies to
     * every 7-day window, so it cannot be circumvented by front-loading one week.
     */
    private void applyMaxHoursPerWeek(CpModel model, BoolVar[][] x, int E, int S, int[] shiftDayIdx) {
        int windowSize = Math.min(7, horizonDays);
        if (windowSize <= 0) return;
        List<List<Integer>> byIndex = new ArrayList<>();
        for (int d = 0; d < horizonDays; d++) byIndex.add(new ArrayList<>());
        for (int s = 0; s < S; s++) byIndex.get(shiftDayIdx[s]).add(s);

        for (int e = 0; e < E; e++) {
            EmployeeDef emp = employees.get(e);
            if (emp.maxHoursPerWeek() <= 0) continue;
            long capMinutes = (long) emp.maxHoursPerWeek() * 60;
            for (int start = 0; start + windowSize <= horizonDays; start++) {
                LinearExprBuilder window = LinearExpr.newBuilder();
                for (int d = start; d < start + windowSize; d++) {
                    for (int s : byIndex.get(d)) window.addTerm(x[e][s], shifts.get(s).durationMinutes());
                }
                model.addLinearConstraint(window, 0, capMinutes);
            }
        }
    }

    private void applyOneShiftPerDay(CpModel model, BoolVar[][] x, int E, int S, int[] shiftDayIdx) {
        // Group shifts by concrete calendar day-index (0 = horizonStart), not by DayOfWeek.
        // This is correct for horizons longer than 7 days where the same DayOfWeek repeats.
        Map<Integer, List<Integer>> byDay = new LinkedHashMap<>();
        for (int s = 0; s < S; s++) {
            byDay.computeIfAbsent(shiftDayIdx[s], k -> new ArrayList<>()).add(s);
        }
        for (int e = 0; e < E; e++) {
            for (List<Integer> dayShifts : byDay.values()) {
                if (dayShifts.size() > 1) {
                    LinearExprBuilder sum = LinearExpr.newBuilder();
                    for (int s : dayShifts) sum.add(x[e][s]);
                    model.addLinearConstraint(sum, 0, 1);
                }
            }
        }
    }

    private void applyMinRest(CpModel model, BoolVar[][] x, int E, int S, Rule.MinRestBetweenShifts rule,
                              int[] shiftDayIdx) {
        int minMinutes = (int) rule.minRest().toMinutes();
        // Pre-compute absolute start/end for each shift using its 0-based day index in the horizon.
        int[] absStart = new int[S];
        int[] absEnd   = new int[S];
        for (int s = 0; s < S; s++) {
            int dayIdx = shiftDayIdx[s];
            absStart[s] = shifts.get(s).absoluteStartMinutes(dayIdx);
            absEnd[s]   = shifts.get(s).absoluteEndMinutes(dayIdx);
        }
        for (int e = 0; e < E; e++) {
            for (int s1 = 0; s1 < S; s1++) {
                for (int s2 = s1 + 1; s2 < S; s2++) {
                    int gapAB = absStart[s2] - absEnd[s1];
                    int gapBA = absStart[s1] - absEnd[s2];
                    boolean tooClose = (gapAB >= 0 && gapAB < minMinutes)
                                    || (gapBA >= 0 && gapBA < minMinutes);
                    if (tooClose) {
                        model.addLinearConstraint(
                                LinearExpr.newBuilder().add(x[e][s1]).add(x[e][s2]).build(), 0, 1);
                    }
                }
            }
        }
    }

    private void applyMaxConsecutiveWorkDays(CpModel model, BoolVar[][] x, int E, int S,
                                             Rule.MaxConsecutiveWorkDays rule, int[] shiftDayIdx) {
        int maxDays = rule.days();
        // Index by calendar-day position (0 = horizonStart), not by DayOfWeek.
        // This is correct for horizons longer than 7 days where the same DayOfWeek repeats.
        List<List<Integer>> byIndex = new ArrayList<>(horizonDays);
        for (int i = 0; i < horizonDays; i++) byIndex.add(new ArrayList<>());
        for (int s = 0; s < S; s++) {
            byIndex.get(shiftDayIdx[s]).add(s);
        }
        int windowSize = maxDays + 1;
        for (int e = 0; e < E; e++) {
            for (int start = 0; start <= horizonDays - windowSize; start++) {
                LinearExprBuilder window = LinearExpr.newBuilder();
                for (int d = start; d < start + windowSize; d++) {
                    for (int s : byIndex.get(d)) window.add(x[e][s]);
                }
                model.addLinearConstraint(window, 0, maxDays);
            }
        }
    }

    private void applyMaxShiftsPerWeek(CpModel model, BoolVar[][] x, int E, int S,
                                       Rule.MaxShiftsPerWeek rule, int[] shiftDayIdx) {
        int maxShifts = rule.maxShifts();
        int windowSize = Math.min(7, horizonDays);
        if (windowSize <= 0) return;
        // Group shift indices by their 0-based horizon day.
        List<List<Integer>> byIndex = new ArrayList<>(horizonDays);
        for (int i = 0; i < horizonDays; i++) byIndex.add(new ArrayList<>());
        for (int s = 0; s < S; s++) {
            byIndex.get(shiftDayIdx[s]).add(s);
        }
        // Every full 7-day (or horizon-wide when shorter) rolling window caps the count.
        for (int e = 0; e < E; e++) {
            for (int start = 0; start + windowSize <= horizonDays; start++) {
                LinearExprBuilder window = LinearExpr.newBuilder();
                for (int d = start; d < start + windowSize; d++) {
                    for (int s : byIndex.get(d)) window.add(x[e][s]);
                }
                model.addLinearConstraint(window, 0, maxShifts);
            }
        }
    }

    /**
     * For every employee, forbids a {@code toCategory} shift on day d+1 after a {@code fromCategory}
     * shift on day d: {@code x[e][s1] + x[e][s2] <= 1} for each such pair of instances.
     */
    private void applyForbidSequence(CpModel model, BoolVar[][] x, int E, int S, Rule.ForbidSequence rule,
                                     int[] shiftDayIdx) {
        for (int s1 = 0; s1 < S; s1++) {
            if (!rule.fromCategory().equals(shifts.get(s1).category())) continue;
            for (int s2 = 0; s2 < S; s2++) {
                if (!rule.toCategory().equals(shifts.get(s2).category())) continue;
                if (shiftDayIdx[s2] != shiftDayIdx[s1] + 1) continue;
                for (int e = 0; e < E; e++) {
                    model.addLinearConstraint(
                            LinearExpr.newBuilder().add(x[e][s1]).add(x[e][s2]).build(), 0, 1);
                }
            }
        }
    }

    private void applyMaxConsecutiveNightShifts(CpModel model, BoolVar[][] x, int E, int S,
                                                Rule.MaxConsecutiveNightShifts rule, int[] shiftDayIdx) {
        int maxNights = rule.maxNights();
        // Identify night shifts (span midnight) and group them by horizon day.
        List<List<Integer>> nightByIndex = new ArrayList<>(horizonDays);
        for (int i = 0; i < horizonDays; i++) nightByIndex.add(new ArrayList<>());
        for (int s = 0; s < S; s++) {
            if (shifts.get(s).isNightShift()) {
                nightByIndex.get(shiftDayIdx[s]).add(s);
            }
        }
        int windowSize = maxNights + 1;
        for (int e = 0; e < E; e++) {
            for (int start = 0; start + windowSize <= horizonDays; start++) {
                LinearExprBuilder window = LinearExpr.newBuilder();
                for (int d = start; d < start + windowSize; d++) {
                    for (int s : nightByIndex.get(d)) window.add(x[e][s]);
                }
                model.addLinearConstraint(window, 0, maxNights);
            }
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    /** Builds a weighted-sum LinearExpr from BoolVars and their coefficients. */
    private static LinearExpr buildWeightedSum(BoolVar[] vars, long[] coefficients) {
        LinearExprBuilder builder = LinearExpr.newBuilder();
        for (int i = 0; i < vars.length; i++) {
            builder.addTerm(vars[i], coefficients[i]);
        }
        return builder.build();
    }

    private void validate() {
        if (employees.isEmpty()) throw new ModelException("At least one employee is required.");
        if (shifts.isEmpty())    throw new ModelException("At least one shift is required.");
        Set<String> employeeIds = new HashSet<>();
        for (EmployeeDef e : employees) {
            if (!employeeIds.add(e.id())) throw new ModelException("Duplicate employee id: " + e.id());
        }
        Set<String> shiftIds = new HashSet<>();
        for (ShiftDef s : shifts) {
            if (!shiftIds.add(s.id())) throw new ModelException("Duplicate shift id: " + s.id());
            if (s.date() == null) {
                throw new ModelException("Shift '" + s.id() + "' has no date.");
            }
            if (s.from() == null || s.to() == null) {
                throw new ModelException("Shift '" + s.id() + "' is missing from or to.");
            }
        }
    }

}

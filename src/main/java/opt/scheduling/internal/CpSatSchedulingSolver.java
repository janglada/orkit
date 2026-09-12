package opt.scheduling.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.scheduling.*;

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

    private double timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    /** Creates a solver bound to the given scheduling model description; inputs are defensively copied. */
    public CpSatSchedulingSolver(List<EmployeeDef> employees,
                                 List<ShiftDef> shifts,
                                 LocalDate horizonStart,
                                 int horizonDays,
                                 List<Rule> rules,
                                 List<SoftGoal> softGoals,
                                 SchedulingGoal objective) {
        this.employees = List.copyOf(employees);
        this.shifts = List.copyOf(shifts);
        this.horizonStart = horizonStart;
        this.horizonDays = horizonDays;
        this.rules = List.copyOf(rules);
        this.softGoals = List.copyOf(softGoals);
        this.objective = objective;
    }

    /** Sets the maximum wall-clock time the solver may run. */
    public CpSatSchedulingSolver timeLimit(Duration limit) {
        if (limit == null) throw new ModelException("timeLimit must not be null.");
        this.timeLimitSeconds = limit.toMillis() / 1000.0;
        return this;
    }
    /** Sets the number of parallel CP-SAT worker threads. */
    public CpSatSchedulingSolver workers(int n)            { this.workers = n;                           return this; }
    /** Sets the random seed for CP-SAT's search randomness. */
    public CpSatSchedulingSolver seed(long s)              { this.seed = s;                              return this; }
    /** Enables or disables CP-SAT search-progress logging to stdout. */
    public CpSatSchedulingSolver verbose(boolean v)        { this.verbose = v;                           return this; }

    /**
     * Builds the CP-SAT model from the configured employees, shifts, rules, soft goals, and
     * objective, then runs the solver to completion or time limit.
     * <p>
     * The model fixes impossible assignments to zero (skill or availability mismatches),
     * enforces each shift's staffing requirement as an equality, applies weekly hour caps and
     * all registered hard {@link Rule}s, and finally minimizes the combined objective (primary
     * {@link SchedulingGoal} plus any {@link SoftGoal} penalties/rewards).
     * @return the solver's status, objective value, runtime, and resulting assignments
     */
    public EmployeeRosterResult solve() {
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

        // Pre-compute the concrete calendar date and 0-based horizon day-index for each shift.
        LocalDate[] shiftDate = new LocalDate[S];
        int[] shiftDayIdx = new int[S];
        for (int s = 0; s < S; s++) {
            ShiftDef shift = shifts.get(s);
            int idx;
            if (shift.specificDate() != null) {
                idx = (int) ChronoUnit.DAYS.between(horizonStart, shift.specificDate());
                if (idx < 0 || idx >= horizonDays) {
                    throw new ModelException("Shift '" + shift.id() + "' date " + shift.specificDate()
                            + " is outside the horizon [" + horizonStart + ", +" + horizonDays + " days)");
                }
            } else {
                idx = horizonDayIndex(shift.day());
                if (idx < 0) {
                    throw new ModelException("Shift '" + shift.id() + "' day " + shift.day()
                            + " does not occur in the horizon");
                }
            }
            shiftDayIdx[s] = idx;
            shiftDate[s] = horizonStart.plusDays(idx);
        }

        // Availability and skill: fix impossible assignments to 0.
        // For a specific-date shift, day() is null — derive the DayOfWeek from the concrete date.
        for (int e = 0; e < E; e++) {
            EmployeeDef emp = employees.get(e);
            for (int s = 0; s < S; s++) {
                ShiftDef shift = shifts.get(s);
                java.time.DayOfWeek dow = shift.day() != null ? shift.day() : shiftDate[s].getDayOfWeek();
                if (!emp.isAvailableOn(dow, shiftDate[s]) || !emp.hasSkill(shift.requiredSkill())) {
                    model.addEquality(x[e][s], 0L);
                }
            }
        }

        // Staffing requirements: for each shift, sum of qualified employees == needs
        for (int s = 0; s < S; s++) {
            ShiftDef shift = shifts.get(s);
            java.time.DayOfWeek dow = shift.day() != null ? shift.day() : shiftDate[s].getDayOfWeek();
            LinearExprBuilder expr = LinearExpr.newBuilder();
            for (int e = 0; e < E; e++) {
                EmployeeDef emp = employees.get(e);
                if (emp.isAvailableOn(dow, shiftDate[s]) && emp.hasSkill(shift.requiredSkill())) {
                    expr.add(x[e][s]);
                }
            }
            model.addEquality(expr, shift.needsCount());
        }

        // Max hours per week
        for (int e = 0; e < E; e++) {
            EmployeeDef emp = employees.get(e);
            if (emp.maxHoursPerWeek() > 0) {
                BoolVar[] vars = new BoolVar[S];
                long[] durations = new long[S];
                for (int s = 0; s < S; s++) {
                    vars[s] = x[e][s];
                    durations[s] = shifts.get(s).durationMinutes();
                }
                model.addLinearConstraint(
                        buildWeightedSum(vars, durations),
                        0, (long) emp.maxHoursPerWeek() * 60);
            }
        }

        // Hard rules
        for (Rule rule : rules) {
            switch (rule) {
                case Rule.OneShiftPerEmployeePerDay r -> applyOneShiftPerDay(model, x, E, S, shiftDayIdx);
                case Rule.MinRestBetweenShifts r      -> applyMinRest(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxConsecutiveWorkDays r    -> applyMaxConsecutiveWorkDays(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxShiftsPerWeek r          -> applyMaxShiftsPerWeek(model, x, E, S, r, shiftDayIdx);
                case Rule.MaxConsecutiveNightShifts r -> applyMaxConsecutiveNightShifts(model, x, E, S, r, shiftDayIdx);
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
        for (SoftGoal goal : softGoals) {
            switch (goal) {
                case SoftGoal.BalanceWorkloadAcrossEmployees g ->
                        hasObjective |= applyBalanceGoal(model, x, E, S, objExpr, g);
                case SoftGoal.HonorEmployeePreferences g ->
                        hasObjective |= applyPreferencesGoal(model, x, E, S, objExpr, g);
            }
        }

        if (hasObjective) {
            model.minimize(objExpr);
        }

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
        boolean feasible = status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;

        List<opt.scheduling.ShiftAssignment> assignments = new ArrayList<>();
        if (feasible) {
            for (int e = 0; e < E; e++) {
                EmployeeDef emp = employees.get(e);
                for (int s = 0; s < S; s++) {
                    if (solver.booleanValue(x[e][s])) {
                        ShiftDef shift = shifts.get(s);
                        LocalDate date = shiftDate[s];
                        assignments.add(new opt.scheduling.ShiftAssignment(
                                emp.id(), shift.id(), date, shift.from(), shift.to()));
                    }
                }
            }
        }

        OptionalDouble objectiveValue = (hasObjective && feasible)
                ? OptionalDouble.of(solver.objectiveValue())
                : OptionalDouble.empty();

        return new EmployeeRosterResult(status, objectiveValue, runtime, assignments);
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
                if (prefs.contains(shifts.get(s).id())) {
                    // Reward (subtract) when preference is satisfied
                    objExpr.addTerm(x[e][s], -g.weight());
                    added = true;
                }
            }
        }
        return added;
    }

    // ── Rule implementations ────────────────────────────────────────────────

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

    /** Returns the 0-based index of the first occurrence of the given DayOfWeek in the horizon, or -1. */
    private int horizonDayIndex(java.time.DayOfWeek dow) {
        for (int i = 0; i < horizonDays; i++) {
            if (horizonStart.plusDays(i).getDayOfWeek() == dow) return i;
        }
        return -1;
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
            if (s.day() == null && s.specificDate() == null) {
                throw new ModelException("Shift '" + s.id() + "' is missing .on(DayOfWeek) or .on(LocalDate).");
            }
            if (s.from() == null || s.to() == null) {
                throw new ModelException("Shift '" + s.id() + "' is missing from or to.");
            }
        }
    }


    /** Raw solver output returned by {@link #solve()}.
     *  @param status solver termination status
     *  @param objective optimized objective value, or empty if no objective was modeled or no solution found
     *  @param runtime wall-clock time spent solving
     *  @param assignments list of employee-to-shift assignments; empty when no feasible solution exists */
    public record EmployeeRosterResult(
            SolveStatus status,
            OptionalDouble objective,
            Duration runtime,
            List<opt.scheduling.ShiftAssignment> assignments
    ) {}
}

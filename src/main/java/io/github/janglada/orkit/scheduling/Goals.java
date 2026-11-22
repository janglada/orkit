package io.github.janglada.orkit.scheduling;

/** Factory for scheduling soft goals and objectives. */
public final class Goals {

    /** Creates a {@link SoftGoal} that penalizes workload imbalance using the default weight. */
    public static SoftGoal balanceWorkloadAcrossEmployees() {
        return new SoftGoal.BalanceWorkloadAcrossEmployees();
    }

    /** Creates a {@link SoftGoal} that penalizes workload imbalance with the given penalty weight. */
    public static SoftGoal balanceWorkloadAcrossEmployees(long weight) {
        return new SoftGoal.BalanceWorkloadAcrossEmployees(weight);
    }

    /** Creates a {@link SoftGoal} that rewards satisfied employee preferences using the default weight. */
    public static SoftGoal honorEmployeePreferences() {
        return new SoftGoal.HonorEmployeePreferences();
    }

    /** Creates a {@link SoftGoal} that rewards satisfied employee preferences with the given reward weight. */
    public static SoftGoal honorEmployeePreferences(long rewardWeight) {
        return new SoftGoal.HonorEmployeePreferences(rewardWeight);
    }

    /**
     * Creates a {@link SoftGoal} that penalizes each employee missing below a shift's
     * {@link ShiftConfig#needs(int) target} (down to its {@link ShiftConfig#needsAtLeast(int) minimum})
     * using the default weight. Applied automatically, with the default weight, whenever a shift
     * declares {@code needsAtLeast} and this goal is absent.
     */
    public static SoftGoal penalizeUnderstaffing() {
        return new SoftGoal.PenalizeUnderstaffing();
    }

    /** Creates a {@link SoftGoal} that penalizes each missing employee on soft-staffed shifts with the given weight. */
    public static SoftGoal penalizeUnderstaffing(long weightPerMissingEmployee) {
        return new SoftGoal.PenalizeUnderstaffing(weightPerMissingEmployee);
    }

    /**
     * Creates a {@link SoftGoal} that penalizes each minute employees work away from their
     * {@link EmployeeConfig#contractHoursPerWeek(int) contracted weekly hours}, using the
     * default weight. Applied automatically, with the default weight, whenever an employee
     * declares contract hours and this goal is absent.
     */
    public static SoftGoal penalizeContractDeviation() {
        return new SoftGoal.PenalizeContractDeviation();
    }

    /** Creates a {@link SoftGoal} that penalizes each minute of contract deviation with the given weight. */
    public static SoftGoal penalizeContractDeviation(long weightPerMinute) {
        return new SoftGoal.PenalizeContractDeviation(weightPerMinute);
    }

    /**
     * Creates the {@link SchedulingGoal} that minimizes total scheduled labor cost. In the
     * objective a shift costs {@code costPerHour * minutes}, the same unit as every soft-goal
     * weight; {@link EmployeeRoster#laborCost()} reports the cost in currency.
     */
    public static SchedulingGoal totalLaborCost() {
        return new SchedulingGoal.MinimizeTotalLaborCost();
    }

    private Goals() {}
}

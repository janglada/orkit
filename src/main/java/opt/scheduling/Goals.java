package opt.scheduling;

/** Factory for scheduling soft goals and objectives. */
public final class Goals {

    public static SoftGoal balanceWorkloadAcrossEmployees() {
        return new SoftGoal.BalanceWorkloadAcrossEmployees();
    }

    public static SoftGoal honorEmployeePreferences() {
        return new SoftGoal.HonorEmployeePreferences();
    }

    public static ObjectiveGoal totalLaborCost() {
        return new ObjectiveGoal.MinimizeTotalLaborCost();
    }

    private Goals() {}
}

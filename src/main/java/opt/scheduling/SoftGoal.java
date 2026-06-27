package opt.scheduling;

/** Soft preference folded into the weighted objective. Create instances via {@link Goals}. */
public sealed interface SoftGoal
        permits SoftGoal.BalanceWorkloadAcrossEmployees, SoftGoal.HonorEmployeePreferences {

    record BalanceWorkloadAcrossEmployees() implements SoftGoal {}

    record HonorEmployeePreferences() implements SoftGoal {}
}

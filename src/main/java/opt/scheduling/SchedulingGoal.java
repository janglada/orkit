package opt.scheduling;

/** Primary minimization objective. Create instances via {@link Goals}. */
public sealed interface SchedulingGoal
        permits SchedulingGoal.MinimizeTotalLaborCost {

    /** Objective that minimizes the sum of {@code costPerHour * scheduled hours} across all assignments. */
    record MinimizeTotalLaborCost() implements SchedulingGoal {}
}

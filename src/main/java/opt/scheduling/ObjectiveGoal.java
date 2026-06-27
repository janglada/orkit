package opt.scheduling;

/** Primary minimization objective. Create instances via {@link Goals}. */
public sealed interface ObjectiveGoal
        permits ObjectiveGoal.MinimizeTotalLaborCost {

    record MinimizeTotalLaborCost() implements ObjectiveGoal {}
}

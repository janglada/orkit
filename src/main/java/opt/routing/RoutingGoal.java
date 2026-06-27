package opt.routing;

/** Minimization objective for a routing problem. Create instances via {@link Goals}. */
public sealed interface RoutingGoal
        permits RoutingGoal.MinimizeTotalDistance {

    record MinimizeTotalDistance() implements RoutingGoal {}
}

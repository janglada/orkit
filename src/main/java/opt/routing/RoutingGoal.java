package opt.routing;

/** Minimization objective for a routing problem. Create instances via {@link Goals}. */
public sealed interface RoutingGoal
        permits RoutingGoal.MinimizeTotalDistance {

    /** Minimize the sum of arc costs (meters) across all vehicles. */
    record MinimizeTotalDistance() implements RoutingGoal {}
}

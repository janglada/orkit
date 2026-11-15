package io.github.janglada.orkit.routing;

/** Optimization goal for a routing problem. Create instances via {@link Goals}. */
public sealed interface RoutingGoal
        permits RoutingGoal.MinimizeTotalDistance, RoutingGoal.MinimizeTotalCost, RoutingGoal.MinimizeVehiclesUsed {

    /** Minimize the sum of arc costs (meters) across all vehicles. Fixed costs still shape the search but are not reported. */
    record MinimizeTotalDistance() implements RoutingGoal {}

    /** Minimize total distance plus the fixed cost of every vehicle that serves at least one stop. */
    record MinimizeTotalCost() implements RoutingGoal {}

    /** Minimize the number of vehicles used, breaking ties by total distance. */
    record MinimizeVehiclesUsed() implements RoutingGoal {}
}

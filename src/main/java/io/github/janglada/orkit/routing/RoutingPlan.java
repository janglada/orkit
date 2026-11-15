package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The solved result of a vehicle routing problem.
 * <p>
 * Routing relies on first-solution strategies followed by local-search metaheuristics,
 * so the solver does not prove global optimality the way CP-SAT does. {@code FEASIBLE}
 * is the normal outcome; {@code OPTIMAL} is reported only on the rare occasion OR-Tools
 * proves it. A search that hits its time limit before finding any solution reports
 * {@code UNKNOWN}; {@code INFEASIBLE} means the search space was exhausted.
 * <p>
 * {@link #objective()} depends on the goal: total meters for {@link Goals#totalDistance()},
 * meters plus fixed costs for {@link Goals#totalCost()}, and the vehicle count for
 * {@link Goals#vehiclesUsed()}.
 */
public final class RoutingPlan implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<Route> routes;
    private final List<String> unassignedStops;

    /**
     * Captures a solved routing result.
     *
     * @param status           solver outcome reported by OR-Tools
     * @param objective        goal-dependent objective value, or empty if no solution was found
     * @param runtime          wall-clock time spent solving
     * @param routes           one {@link Route} per vehicle that was assigned stops (empty vehicles omitted)
     * @param unassignedStops  stop ids the solver dropped or could not serve
     */
    public RoutingPlan(SolveStatus status, OptionalDouble objective, Duration runtime,
                       List<Route> routes, List<String> unassignedStops) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.routes = Collections.unmodifiableList(routes);
        this.unassignedStops = Collections.unmodifiableList(unassignedStops);
    }

    /** Reports the solver outcome; {@code FEASIBLE} is the normal case for routing. */
    @Override public SolveStatus status()       { return status; }
    /** Goal-dependent objective (see class javadoc), or empty when no solution was found. */
    @Override public OptionalDouble objective() { return objective; }
    /** Wall-clock time spent solving. */
    @Override public Duration runtime()         { return runtime; }

    /** One {@link Route} per vehicle that received stops; idle vehicles are omitted. */
    public List<Route> routes() {
        return routes;
    }

    /** Stop ids the solver dropped (when dropping is allowed) or could not serve. */
    public List<String> unassignedStops() {
        return unassignedStops;
    }

    /** The route of the given vehicle; empty when the vehicle is idle or unknown. */
    public Optional<Route> routeOf(String vehicleId) {
        return routes.stream().filter(r -> r.vehicle().equals(vehicleId)).findFirst();
    }

    /** Number of vehicles that serve at least one stop. */
    public int vehiclesUsed() {
        return routes.size();
    }

    /** Sum of {@link Route#distanceMeters()} over all routes. */
    public long totalDistanceMeters() {
        return routes.stream().mapToLong(Route::distanceMeters).sum();
    }

    /** Sum of {@link Route#fixedCost()} over all used vehicles. */
    public long totalFixedCost() {
        return routes.stream().mapToLong(Route::fixedCost).sum();
    }
}

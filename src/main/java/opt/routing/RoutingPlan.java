package opt.routing;

import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/** The solved result of a vehicle routing problem. */
public final class RoutingPlan implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<Route> routes;
    private final List<String> unassignedStops;

    public RoutingPlan(SolveStatus status, OptionalDouble objective, Duration runtime,
                       List<Route> routes, List<String> unassignedStops) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.routes = Collections.unmodifiableList(routes);
        this.unassignedStops = Collections.unmodifiableList(unassignedStops);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }

    public List<Route> routes() {
        return routes;
    }

    public List<String> unassignedStops() {
        return unassignedStops;
    }
}

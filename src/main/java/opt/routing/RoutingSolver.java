package opt.routing;

import opt.Solver;
import opt.routing.internal.RoutingCompiler;
import opt.routing.internal.StopDef;
import opt.routing.internal.VehicleDef;

import java.time.Duration;
import java.util.List;

/**
 * Routing-specific solver that adds {@link #firstSolution} and {@link #improveWith}
 * on top of the shared {@link Solver} interface.
 */
public final class RoutingSolver implements Solver<RoutingPlan> {

    private final String depotId;
    private final double depotLat;
    private final double depotLon;
    private final List<VehicleDef> vehicles;
    private final List<StopDef> stops;
    private final DistanceSource distanceSource;
    private final long droppingPenalty;
    private final RoutingGoal goal;
    private final double travelSpeedKmh;

    private Duration timeLimit = Duration.ofSeconds(30);
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;
    private Strategy firstSolutionStrategy = Strategy.AUTOMATIC;
    private Metaheuristic metaheuristic = Metaheuristic.AUTOMATIC;

    RoutingSolver(String depotId, double depotLat, double depotLon,
                  List<VehicleDef> vehicles, List<StopDef> stops,
                  DistanceSource distanceSource, long droppingPenalty,
                  RoutingGoal goal, double travelSpeedKmh) {
        this.depotId = depotId;
        this.depotLat = depotLat;
        this.depotLon = depotLon;
        this.vehicles = vehicles;
        this.stops = stops;
        this.distanceSource = distanceSource;
        this.droppingPenalty = droppingPenalty;
        this.goal = goal;
        this.travelSpeedKmh = travelSpeedKmh;
    }

    /** Sets the wall-clock time budget for the search; default 30 seconds. */
    @Override public RoutingSolver timeLimit(Duration limit) { this.timeLimit = limit;   return this; }
    /** Sets the number of parallel search workers; default 1. */
    @Override public RoutingSolver workers(int threads)       { this.workers = threads;   return this; }
    /** Seeds the randomized local-search component for reproducibility. */
    @Override public RoutingSolver seed(long randomSeed)      { this.seed = randomSeed;   return this; }
    /** Enables OR-Tools search log output. */
    @Override public RoutingSolver verbose(boolean log)       { this.verbose = log;       return this; }

    /**
     * Overrides the first-solution strategy used to construct the initial routes before
     * local search begins; default {@link Strategy#AUTOMATIC}.
     */
    public RoutingSolver firstSolution(Strategy strategy) {
        this.firstSolutionStrategy = strategy;
        return this;
    }

    /**
     * Overrides the local-search metaheuristic applied after the first solution is built;
     * default {@link Metaheuristic#AUTOMATIC}.
     */
    public RoutingSolver improveWith(Metaheuristic meta) {
        this.metaheuristic = meta;
        return this;
    }

    /** Solves the configured routing problem and returns the resulting {@link RoutingPlan}. */
    @Override
    public RoutingPlan solve() {
        return new RoutingCompiler(
                depotId, depotLat, depotLon,
                vehicles, stops, distanceSource, droppingPenalty,
                firstSolutionStrategy, metaheuristic, goal, travelSpeedKmh)
                .timeLimit(timeLimit)
                .workers(workers)
                .seed(seed)
                .verbose(verbose)
                .solve();
    }
}

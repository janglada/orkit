package opt.routing;

import opt.Solver;
import opt.routing.internal.RoutingCompiler;
import opt.routing.internal.RoutingSpec;

import java.time.Duration;

/**
 * Routing-specific solver that adds {@link #firstSolution} and {@link #improveWith}
 * on top of the shared {@link Solver} interface.
 * <p>
 * Unlike the CP-SAT domains, routing exposes no worker count or random seed: the
 * OR-Tools routing search parameters have no such settings, so accepting them here
 * would silently do nothing.
 */
public final class RoutingSolver implements Solver<RoutingPlan> {

    private final RoutingSpec spec;

    private Duration timeLimit = Duration.ofSeconds(30);
    private boolean verbose = false;
    private Strategy firstSolutionStrategy = Strategy.AUTOMATIC;
    private Metaheuristic metaheuristic = Metaheuristic.AUTOMATIC;

    RoutingSolver(RoutingSpec spec) {
        this.spec = spec;
    }

    /** Sets the wall-clock time budget for the search; default 30 seconds. */
    @Override public RoutingSolver timeLimit(Duration limit) { this.timeLimit = limit;   return this; }
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
        return new RoutingCompiler(spec, firstSolutionStrategy, metaheuristic)
                .timeLimit(timeLimit)
                .verbose(verbose)
                .solve();
    }
}

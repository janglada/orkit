package opt.routing;

import opt.ModelException;
import opt.Solver;
import opt.internal.Validate;
import opt.routing.internal.RoutingCompiler;
import opt.routing.internal.RoutingSpec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routing-specific solver that adds {@link #firstSolution} and {@link #improveWith}
 * on top of the shared {@link Solver} interface.
 * <p>
 * Unlike the CP-SAT domains, routing exposes no worker count or random seed: the
 * OR-Tools routing search parameters have no such settings, so accepting them here
 * would silently do nothing. {@link #cancel()} stops a running search and keeps the best
 * routes found so far.
 */
public final class RoutingSolver implements Solver<RoutingPlan> {

    private final RoutingSpec spec;

    private Duration timeLimit = Duration.ofSeconds(30);
    private boolean verbose = false;
    private Strategy firstSolutionStrategy = Strategy.AUTOMATIC;
    private Metaheuristic metaheuristic = Metaheuristic.AUTOMATIC;
    private final AtomicBoolean solving = new AtomicBoolean();
    private volatile boolean cancelRequested;

    RoutingSolver(RoutingSpec spec) {
        this.spec = spec;
    }

    /** Sets the wall-clock time budget for the search; default 30 seconds. Must be positive. */
    @Override
    public RoutingSolver timeLimit(Duration limit) {
        Validate.positive(limit, "timeLimit");
        this.timeLimit = limit;
        return this;
    }
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

    /**
     * Solves the configured routing problem and returns the resulting {@link RoutingPlan}.
     *
     * @throws ModelException if this solver is already solving on another thread
     */
    @Override
    public RoutingPlan solve() {
        if (!solving.compareAndSet(false, true)) {
            throw new ModelException("solve() is already running on this solver; create a new solver per concurrent solve.");
        }
        cancelRequested = false;
        try {
            return new RoutingCompiler(spec, firstSolutionStrategy, metaheuristic)
                    .timeLimit(timeLimit)
                    .verbose(verbose)
                    .stopWhen(() -> cancelRequested)
                    .solve();
        } finally {
            cancelRequested = false;
            solving.set(false);
        }
    }

    /**
     * Asks a running solve to stop as soon as possible; {@link #solve()} then returns the best
     * routes found so far, or status {@code UNKNOWN} if none was found yet. Safe to call from
     * another thread. A no-op when no solve is running.
     */
    public void cancel() {
        if (solving.get()) cancelRequested = true;
    }
}

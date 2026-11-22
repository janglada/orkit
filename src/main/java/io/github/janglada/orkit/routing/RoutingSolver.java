package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Solver;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.routing.internal.RoutingCompiler;
import io.github.janglada.orkit.routing.internal.RoutingSpec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private Consumer<RoutingPlan> listener;
    private RoutingPlan warmStart;

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
     * Registers a listener invoked for every solution the search accepts. The listener receives
     * a fully built plan whose status is {@link io.github.janglada.orkit.SolveStatus#FEASIBLE}; it
     * runs on the solver thread and should return quickly. Arrival times on intermediate plans
     * are the earliest consistent with the routes. An exception thrown by the listener stops the
     * search and is rethrown from {@link #solve()} wrapped in a {@link ModelException}; an
     * {@link Error} is rethrown as is. The listener may call {@link #cancel()}.
     *
     * @throws ModelException if {@code onSolution} is null
     */
    public RoutingSolver onSolution(Consumer<RoutingPlan> onSolution) {
        if (onSolution == null) throw new ModelException("onSolution listener must not be null.");
        this.listener = onSolution;
        return this;
    }

    /**
     * Starts the search from the routes of a previous plan instead of a first-solution strategy.
     * Vehicles and stops that no longer exist are ignored. If the previous routes are not
     * feasible for this model, the search starts from scratch as usual.
     *
     * @throws ModelException if {@code previous} is null or not feasible
     */
    public RoutingSolver warmStart(RoutingPlan previous) {
        if (previous == null) throw new ModelException("warmStart solution must not be null.");
        if (!previous.isFeasible()) {
            throw new ModelException("warmStart solution must be feasible, got status " + previous.status() + ".");
        }
        this.warmStart = previous;
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
                    .onSolution(listener)
                    .warmStart(warmStart)
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

package io.github.janglada.orkit;

import java.time.Duration;

/**
 * Configurable solver for an {@link OptimizationProblem}, obtained via
 * {@link OptimizationProblem#solver()}.
 * <p>
 * This interface carries only the knobs every backend honors. CP-SAT backed
 * domains return a {@link CpSatSolver}, which adds worker count, random seed,
 * intermediate-solution callbacks, warm starts, and cancellation. The routing
 * domain returns {@link io.github.janglada.orkit.routing.RoutingSolver}, which adds first-solution
 * and metaheuristic selection instead (OR-Tools routing search exposes no
 * worker count or seed).
 * <p>
 * The setter methods return {@code this} so calls can be chained, terminating in
 * {@link #solve()}.
 */
public interface Solver<S extends Solution> {

    /**
     * Sets the maximum wall-clock time the solver may run before giving up.
     *
     * @throws ModelException if {@code limit} is null, zero, or negative
     */
    Solver<S> timeLimit(Duration limit);

    /** Enables or disables logging to standard output during the solve. */
    Solver<S> verbose(boolean logToStdout);

    /**
     * Runs the solver and returns the resulting {@link Solution}. A solver runs one
     * solve at a time; obtain a separate solver for each concurrent solve.
     *
     * @return the solution produced by the solve
     */
    S solve();
}

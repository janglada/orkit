package opt;

import java.time.Duration;

/**
 * Configurable solver for an {@link OptimizationProblem}, obtained via
 * {@link OptimizationProblem#solver()}.
 * <p>
 * This interface carries only the knobs every backend honors. CP-SAT backed
 * domains return a {@link CpSatSolver}, which adds worker count, random seed,
 * intermediate-solution callbacks, warm starts, and cancellation. The routing
 * domain returns {@link opt.routing.RoutingSolver}, which adds first-solution
 * and metaheuristic selection instead (OR-Tools routing search exposes no
 * worker count or seed).
 * <p>
 * The setter methods return {@code this} so calls can be chained, terminating in
 * {@link #solve()}.
 */
public interface Solver<S extends Solution> {

    /** Sets the maximum wall-clock time the solver may run before giving up. */
    Solver<S> timeLimit(Duration limit);

    /** Enables or disables logging to standard output during the solve. */
    Solver<S> verbose(boolean logToStdout);

    /**
     * Runs the solver and returns the resulting {@link Solution}.
     *
     * @return the solution produced by the solve
     */
    S solve();
}

package opt;

import java.time.Duration;

/**
 * Configurable solver for an {@link OptimizationProblem}, obtained via
 * {@link OptimizationProblem#solver()}.
 * <p>
 * The setter methods return {@code this} so calls can be chained, terminating in
 * {@link #solve()}.
 */
public interface Solver<S extends Solution> {

    /** Sets the maximum wall-clock time the solver may run before giving up. */
    Solver<S> timeLimit(Duration limit);

    /** Sets the number of parallel search workers (threads). */
    Solver<S> workers(int threads);

    /** Sets the random seed used to make search reproducible. */
    Solver<S> seed(long randomSeed);

    /** Enables or disables logging to standard output during the solve. */
    Solver<S> verbose(boolean logToStdout);

    /**
     * Runs the solver and returns the resulting {@link Solution}.
     *
     * @return the solution produced by the solve
     */
    S solve();
}

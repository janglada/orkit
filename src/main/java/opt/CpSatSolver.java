package opt;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * {@link Solver} backed by CP-SAT. Adds the knobs CP-SAT honors on top of the
 * shared interface: parallel workers, random seed, intermediate-solution
 * callbacks, warm starts from a previous solution, and cancellation.
 * <p>
 * Every domain except routing returns this type from {@code solver()}.
 */
public interface CpSatSolver<S extends Solution> extends Solver<S> {

    @Override
    CpSatSolver<S> timeLimit(Duration limit);

    @Override
    CpSatSolver<S> verbose(boolean logToStdout);

    /**
     * Sets the number of parallel search workers. Default 8, which is the
     * portfolio size CP-SAT is tuned for; must be at least 1.
     */
    CpSatSolver<S> workers(int threads);

    /**
     * Sets the random seed. Runs are reproducible for the same seed only when
     * {@link #workers(int)} is 1; with several workers the interleaving of
     * subsolvers is not deterministic.
     */
    CpSatSolver<S> seed(int randomSeed);

    /**
     * Registers a listener invoked for every improving intermediate solution
     * found during the search. The listener receives a fully built result whose
     * status is {@link SolveStatus#FEASIBLE}; it runs on the solver thread and
     * should return quickly. An exception thrown by the listener stops the
     * search and is rethrown from {@link #solve()} wrapped in a
     * {@link ModelException}.
     */
    CpSatSolver<S> onSolution(Consumer<S> listener);

    /**
     * Uses a previous feasible solution as search hints (warm start). Ids in the
     * previous solution that no longer exist in the model are ignored, so a
     * result can be reused after the model has been edited.
     *
     * @throws ModelException if {@code previous} is null or not feasible
     */
    CpSatSolver<S> warmStart(S previous);

    /**
     * Asks a running solve to stop as soon as possible; {@link #solve()} then
     * returns the best solution found so far. Safe to call from another thread
     * or from an {@link #onSolution} listener. A no-op when no solve is running.
     */
    void cancel();
}

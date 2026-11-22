package io.github.janglada.orkit;

import java.nio.file.Path;
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

    /** {@inheritDoc} Must be positive. */
    @Override
    CpSatSolver<S> timeLimit(Duration limit);

    @Override
    CpSatSolver<S> verbose(boolean logToStdout);

    /**
     * Sets the number of parallel search workers; must be at least 1. Default:
     * 8 (the portfolio size CP-SAT is tuned for), capped at the number of
     * processors available to the JVM.
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
     * {@link ModelException}; an {@link Error} is rethrown as is.
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
     * Writes the CP-SAT model to {@code file} just before each search, for inspection or
     * for reproducing a solve outside this library. The file is text protobuf when its name
     * ends in {@code .txt} or {@code .textproto}, binary protobuf otherwise. The model is
     * validated first; {@link #solve()} then throws {@link ModelException} if the model is
     * invalid or the file cannot be written.
     *
     * @throws ModelException if {@code file} is null
     */
    CpSatSolver<S> exportModel(Path file);

    /**
     * When the model turns out infeasible, solves it once more with its main constraint groups
     * labeled (for example a shift's staffing, an operation's deadline, the pieces of one type)
     * and reports the labels of a set of groups that cannot hold together in
     * {@link SolveDiagnostics#infeasibilityReasons()}. The set is sufficient for infeasibility
     * but not necessarily minimal, and constraints the library does not label (such as
     * no-overlap) are always assumed. Off by default; costs one extra single-worker solve, and
     * only on infeasible models.
     */
    CpSatSolver<S> explainInfeasibility(boolean explain);

    /**
     * Asks a running solve to stop as soon as possible; {@link #solve()} then
     * returns the best solution found so far. A solve is running from the moment
     * {@link #solve()} is entered, so a cancel issued while the model is still
     * being built is honored too. Safe to call from another thread or from an
     * {@link #onSolution} listener. A no-op when no solve is running.
     */
    void cancel();
}

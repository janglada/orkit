package io.github.janglada.orkit.internal;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.SatParameters;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared CP-SAT search configuration and solve loop used by every CP-SAT domain.
 * Replaces the per-solver copies of the parameter block, the timing code, and the
 * status mapping, and implements intermediate-solution callbacks and cancellation.
 */
public final class CpSatParams {

    /**
     * Default number of parallel workers: CP-SAT's subsolver portfolio is tuned for 8, but
     * never more than the processors available to the JVM (which honors container CPU limits).
     */
    public static final int DEFAULT_WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    /** Default wall-clock time limit. */
    public static final Duration DEFAULT_TIME_LIMIT = Duration.ofMinutes(1);

    private double timeLimitSeconds = DEFAULT_TIME_LIMIT.toMillis() / 1000.0;
    private int workers = DEFAULT_WORKERS;
    private int seed = 0;
    private boolean verbose = false;
    private final AtomicReference<CpSolver> active = new AtomicReference<>();
    private final AtomicBoolean inSession = new AtomicBoolean();
    private volatile boolean cancelRequested;

    /** Loads the native library eagerly; see {@link AbstractCpSatSolver}. */
    public CpSatParams() {
        NativeLoader.ensureLoaded();
    }

    /** Builds the domain result from solved values; called for the final solution and for each intermediate one. */
    @FunctionalInterface
    public interface Extractor<S extends Solution> {
        /**
         * @param values  solved variable values
         * @param status  {@link SolveStatus#FEASIBLE} for intermediate solutions, the mapped final status otherwise
         * @param runtime wall-clock time elapsed since the solve started
         */
        S extract(CpValues values, SolveStatus status, Duration runtime);
    }

    /** Sets the time limit; rejects null, zero, and negative durations. */
    public CpSatParams timeLimit(Duration limit) {
        Validate.positive(limit, "timeLimit");
        this.timeLimitSeconds = limit.toNanos() / 1e9;
        return this;
    }

    /** Sets the worker count; must be at least 1. */
    public CpSatParams workers(int n) {
        Validate.atLeastOne(n, "workers");
        this.workers = n;
        return this;
    }

    /** Sets the random seed. */
    public CpSatParams seed(int s) {
        this.seed = s;
        return this;
    }

    /** Enables or disables search-progress logging. */
    public CpSatParams verbose(boolean v) {
        this.verbose = v;
        return this;
    }

    /**
     * Marks the start of a solve session, which spans model building and the search.
     * A {@link #cancel()} during the session stops the search, even if it arrives before
     * the search has started.
     *
     * @throws ModelException if a session is already running (concurrent solve on one solver)
     */
    public void beginSession() {
        if (!inSession.compareAndSet(false, true)) {
            throw new ModelException("solve() is already running on this solver; create a new solver per concurrent solve.");
        }
        cancelRequested = false;
    }

    /** Ends the current solve session; a later {@link #cancel()} is a no-op until the next one. */
    public void endSession() {
        cancelRequested = false;
        inSession.set(false);
    }

    /** Stops the current solve session, if any; a no-op when nothing is solving. */
    public void cancel() {
        if (!inSession.get()) return;
        // Publish the request before reading the active solver: the solve thread publishes the
        // solver before reading the request, so at least one side always sees the other.
        cancelRequested = true;
        CpSolver solver = active.get();
        if (solver != null) solver.stopSearch();
    }

    /**
     * Configures a {@link CpSolver} with these parameters, solves {@code model}, and
     * returns {@code extractor}'s result for the final status. When {@code listener}
     * is non-null, every improving solution found during the search is also
     * extracted (with status {@code FEASIBLE}) and handed to the listener.
     */
    public <S extends Solution> S solve(CpModel model, Extractor<S> extractor, Consumer<S> listener) {
        NativeLoader.ensureLoaded();
        CpSolver solver = new CpSolver();
        SatParameters.Builder p = solver.getParameters();
        p.setMaxTimeInSeconds(timeLimitSeconds);
        p.setNumWorkers(workers);
        p.setRandomSeed(seed);
        p.setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        ListenerCallback<S> callback = (listener == null) ? null
                : new ListenerCallback<>(extractor, listener, startMs);

        CpSolverStatus cpStatus;
        active.set(solver);
        // A cancel that arrived while the model was being built: return at once with whatever
        // the zero-budget search yields (normally UNKNOWN).
        if (cancelRequested) p.setMaxTimeInSeconds(0.0);
        try {
            cpStatus = (callback == null) ? solver.solve(model) : solver.solve(model, callback);
        } finally {
            active.set(null);
        }
        if (callback != null && callback.failure != null) {
            // Errors (OutOfMemoryError, AssertionError, ...) are not model problems; let them through.
            if (callback.failure instanceof Error e) throw e;
            throw new ModelException("onSolution listener threw an exception; search was stopped.", callback.failure);
        }
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);
        return extractor.extract(CpValues.of(solver), mapStatus(cpStatus), runtime);
    }

    /** Maps a CP-SAT status to the public {@link SolveStatus}. */
    public static SolveStatus mapStatus(CpSolverStatus cpSolverStatus) {
        return switch (cpSolverStatus) {
            case OPTIMAL       -> SolveStatus.OPTIMAL;
            case FEASIBLE      -> SolveStatus.FEASIBLE;
            case INFEASIBLE    -> SolveStatus.INFEASIBLE;
            case MODEL_INVALID -> SolveStatus.MODEL_INVALID;
            default            -> SolveStatus.UNKNOWN;
        };
    }

    /** Bridges CP-SAT's solution callback to the domain extractor and the user listener. */
    private static final class ListenerCallback<S extends Solution> extends CpSolverSolutionCallback {
        private final Extractor<S> extractor;
        private final Consumer<S> listener;
        private final long startMs;
        volatile Throwable failure;

        ListenerCallback(Extractor<S> extractor, Consumer<S> listener, long startMs) {
            this.extractor = extractor;
            this.listener = listener;
            this.startMs = startMs;
        }

        @Override
        public void onSolutionCallback() {
            if (failure != null) return;
            try {
                Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startMs);
                listener.accept(extractor.extract(CpValues.of(this), SolveStatus.FEASIBLE, elapsed));
            } catch (Throwable t) {
                failure = t;
                stopSearch();
            }
        }
    }
}

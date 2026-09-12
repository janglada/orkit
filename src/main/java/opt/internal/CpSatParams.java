package opt.internal;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.SatParameters;
import opt.ModelException;
import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared CP-SAT search configuration and solve loop used by every CP-SAT domain.
 * Replaces the per-solver copies of the parameter block, the timing code, and the
 * status mapping, and implements intermediate-solution callbacks and cancellation.
 */
public final class CpSatParams {

    /** Default number of parallel workers; CP-SAT's subsolver portfolio is tuned for 8. */
    public static final int DEFAULT_WORKERS = 8;
    /** Default wall-clock time limit. */
    public static final Duration DEFAULT_TIME_LIMIT = Duration.ofMinutes(1);

    private double timeLimitSeconds = DEFAULT_TIME_LIMIT.toMillis() / 1000.0;
    private int workers = DEFAULT_WORKERS;
    private int seed = 0;
    private boolean verbose = false;
    private final AtomicReference<CpSolver> active = new AtomicReference<>();

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

    /** Sets the time limit; rejects null. */
    public CpSatParams timeLimit(Duration limit) {
        if (limit == null) throw new ModelException("timeLimit must not be null.");
        this.timeLimitSeconds = limit.toMillis() / 1000.0;
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

    /** Stops the running solve, if any. */
    public void cancel() {
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
        try {
            cpStatus = (callback == null) ? solver.solve(model) : solver.solve(model, callback);
        } finally {
            active.set(null);
        }
        if (callback != null && callback.failure != null) {
            throw new ModelException("onSolution listener threw an exception; search was stopped.", callback.failure);
        }
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);
        return extractor.extract(CpValues.of(solver), SolveStatus.mapStatus(cpStatus), runtime);
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

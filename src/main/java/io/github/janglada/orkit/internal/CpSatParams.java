package io.github.janglada.orkit.internal;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.SatParameters;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
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
    private Path exportPath;
    private boolean explainInfeasibility;
    private boolean explaining;
    private ConstraintGroups groups = ConstraintGroups.off();
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
         * @param diagnostics search statistics for this solution
         */
        S extract(CpValues values, SolveStatus status, Duration runtime, SolveDiagnostics diagnostics);
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
     * Writes the built model to {@code file} before each search: text format when the name
     * ends in {@code .txt} or {@code .textproto}, binary protobuf otherwise.
     */
    public CpSatParams exportModel(Path file) {
        if (file == null) throw new ModelException("exportModel path must not be null.");
        this.exportPath = file;
        return this;
    }

    /** Requests a second, explaining solve when the first one proves the model infeasible. */
    public CpSatParams explainInfeasibility(boolean explain) {
        this.explainInfeasibility = explain;
        return this;
    }

    /** Whether an explaining solve was requested. */
    public boolean explainInfeasibilityRequested() {
        return explainInfeasibility;
    }

    /** Switches the explaining mode used by the next model built through {@link #groups(CpModel)}. */
    public void explaining(boolean on) {
        this.explaining = on;
    }

    /**
     * The constraint groups for a model about to be built: active while explaining, inactive
     * otherwise. Domain solvers guard the constraints worth naming through them.
     */
    public ConstraintGroups groups(CpModel model) {
        groups = explaining ? ConstraintGroups.on(model) : ConstraintGroups.off();
        return groups;
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
        boolean explain = groups.active() && groups.isFor(model);
        if (explain) {
            // Cores of failed assumptions are reported by the single-worker search.
            groups.assumeAll();
            p.setNumWorkers(1);
        }
        if (exportPath != null) export(model);
        boolean hasObjective = model.getBuilder().hasObjective();

        long startNs = System.nanoTime();
        ListenerCallback<S> callback = (listener == null) ? null
                : new ListenerCallback<>(extractor, listener, startNs, hasObjective);

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
        Duration runtime = Duration.ofNanos(System.nanoTime() - startNs);
        SolveStatus status = mapStatus(cpStatus);
        CpValues values = CpValues.of(solver);
        SolveDiagnostics diag = diagnostics(values, status, hasObjective);
        if (explain && status == SolveStatus.INFEASIBLE) {
            diag = diag.withInfeasibilityReasons(groups.labels(solver.sufficientAssumptionsForInfeasibility()));
        }
        return extractor.extract(values, status, runtime, diag);
    }

    /** Validates {@code model} and writes it to the export path. */
    private void export(CpModel model) {
        String problem = model.validate();
        if (problem != null && !problem.isEmpty()) {
            throw new ModelException("The built CP-SAT model is invalid: " + problem);
        }
        if (!Boolean.TRUE.equals(model.exportToFile(exportPath.toString()))) {
            throw new ModelException("Could not write the model to '" + exportPath + "'.");
        }
    }

    /**
     * Builds the diagnostics for a result: the bound is reported only for a model with an
     * objective and a solution, and only when finite.
     */
    static SolveDiagnostics diagnostics(CpValues values, SolveStatus status, boolean hasObjective) {
        boolean solved = status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;
        OptionalDouble bound = OptionalDouble.empty();
        if (hasObjective && solved) {
            double b = values.bestObjectiveBound();
            if (Double.isFinite(b)) bound = OptionalDouble.of(b);
        }
        return new SolveDiagnostics(bound, values.numBranches(), values.numConflicts(), List.of());
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
        private final long startNs;
        private final boolean hasObjective;
        volatile Throwable failure;

        ListenerCallback(Extractor<S> extractor, Consumer<S> listener, long startNs, boolean hasObjective) {
            this.extractor = extractor;
            this.listener = listener;
            this.startNs = startNs;
            this.hasObjective = hasObjective;
        }

        @Override
        public void onSolutionCallback() {
            if (failure != null) return;
            try {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
                CpValues values = CpValues.of(this);
                listener.accept(extractor.extract(values, SolveStatus.FEASIBLE, elapsed,
                        diagnostics(values, SolveStatus.FEASIBLE, hasObjective)));
            } catch (Throwable t) {
                failure = t;
                stopSearch();
            }
        }
    }
}

package opt.internal;

import opt.CpSatSolver;
import opt.ModelException;
import opt.Solution;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Base class for the per-domain {@link CpSatSolver} implementations: holds the
 * shared {@link CpSatParams}, the optional solution listener, and the optional
 * warm-start solution. Subclasses implement only {@link #solve()}.
 */
public abstract class AbstractCpSatSolver<S extends Solution> implements CpSatSolver<S> {

    /** Search parameters shared with the internal CP-SAT solver. */
    protected final CpSatParams params = new CpSatParams();
    /** Intermediate-solution listener, or null. */
    protected Consumer<S> listener;
    /** Previous solution used for hints, or null. */
    protected S warmStart;

    /**
     * Loads the OR-Tools native library as soon as a solver exists: CP-SAT variable creation
     * already crosses into JNI, so loading only at solve time would be too late for callers
     * that construct a builder directly instead of going through {@code Optimize}.
     */
    protected AbstractCpSatSolver() {
        NativeLoader.ensureLoaded();
    }

    @Override public CpSatSolver<S> timeLimit(Duration limit)      { params.timeLimit(limit);  return this; }
    @Override public CpSatSolver<S> verbose(boolean logToStdout)   { params.verbose(logToStdout); return this; }
    @Override public CpSatSolver<S> workers(int threads)           { params.workers(threads);  return this; }
    @Override public CpSatSolver<S> seed(int randomSeed)           { params.seed(randomSeed);  return this; }

    @Override
    public CpSatSolver<S> onSolution(Consumer<S> listener) {
        if (listener == null) throw new ModelException("onSolution listener must not be null.");
        this.listener = listener;
        return this;
    }

    @Override
    public CpSatSolver<S> warmStart(S previous) {
        if (previous == null) throw new ModelException("warmStart solution must not be null.");
        if (!previous.isFeasible()) {
            throw new ModelException("warmStart solution must be feasible, got status " + previous.status() + ".");
        }
        this.warmStart = previous;
        return this;
    }

    @Override
    public void cancel() {
        params.cancel();
    }
}

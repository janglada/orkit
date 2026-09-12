package opt;

/**
 * A built, immutable CP-SAT problem that yields a {@link CpSatSolver} (not just a
 * generic {@link Solver}) so that workers, seed, callbacks, and warm starts are
 * available without a cast.
 */
public interface CpSatProblem<S extends Solution> extends OptimizationProblem<S> {

    /** Returns a fresh {@link CpSatSolver} bound to this problem. */
    @Override
    CpSatSolver<S> solver();
}

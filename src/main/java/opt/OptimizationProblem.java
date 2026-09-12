package opt;

import java.util.List;

/**
 * A constrained optimization problem that can be solved to produce a {@link Solution}.
 * <p>
 * Implementations are typically fluent builders obtained from {@link Optimize}.
 */
public interface OptimizationProblem<S extends Solution> {

    /**
     * Returns a fresh {@link Solver} bound to this problem, ready to configure and run.
     *
     * @return a new solver for this problem
     */
    Solver<S> solver();

    /**
     * Non-fatal diagnostics collected while building the model (for example, a
     * setup type declared in a matrix but used by no operation). Empty by default.
     */
    default List<String> warnings() {
        return List.of();
    }
}

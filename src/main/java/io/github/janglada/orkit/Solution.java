package io.github.janglada.orkit;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * The result of solving an {@link OptimizationProblem}: the solver status, the
 * objective value reached, and how long the solve took.
 * <p>
 * Use {@link #isFeasible()} and {@link #isOptimal()} for the most common status checks.
 */
public interface Solution {

    /** Returns the {@link SolveStatus} reported by the underlying solver. */
    SolveStatus status();

    /**
     * Returns the objective value of the solution, if one was found.
     *
     * @return the objective value, or empty if no solution was produced
     */
    OptionalDouble objective();

    /** Returns the wall-clock time spent by the solver. */
    Duration runtime();

    /**
     * Whether the solver produced a usable (optimal or merely feasible) solution.
     *
     * @return {@code true} if {@link #status()} is {@link SolveStatus#OPTIMAL}
     *         or {@link SolveStatus#FEASIBLE}
     */
    default boolean isFeasible() {
        return status() == SolveStatus.OPTIMAL || status() == SolveStatus.FEASIBLE;
    }

    /**
     * Whether the solver proved global optimality.
     * <p>
     * For routing this is rarely true, because routing uses heuristics
     * (first-solution strategies + local-search metaheuristics) rather than
     * proving optimality; expect {@code FEASIBLE} there.
     *
     * @return {@code true} if {@link #status()} is {@link SolveStatus#OPTIMAL}
     */
    default boolean isOptimal() {
        return status() == SolveStatus.OPTIMAL;
    }

    /**
     * Search statistics for this result: best proven bound, branches, conflicts and, when
     * requested, an explanation of infeasibility. {@link SolveDiagnostics#none()} by default.
     */
    default SolveDiagnostics diagnostics() {
        return SolveDiagnostics.none();
    }

    /**
     * The best objective bound the solver proved, if any: no feasible solution can beat it.
     * Empty for routing, for models without an objective, and when no solution was found.
     */
    default OptionalDouble bestBound() {
        return diagnostics().bestBound();
    }

    /**
     * Relative optimality gap {@code |objective - bound| / max(1, |objective|)}: {@code 0}
     * for a proven optimum, empty when either the objective or the bound is missing.
     */
    default OptionalDouble gap() {
        OptionalDouble objective = objective();
        OptionalDouble bound = bestBound();
        if (objective.isEmpty() || bound.isEmpty()) return OptionalDouble.empty();
        double obj = objective.getAsDouble();
        return OptionalDouble.of(Math.abs(obj - bound.getAsDouble()) / Math.max(1.0, Math.abs(obj)));
    }
}

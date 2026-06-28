package opt;

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
}

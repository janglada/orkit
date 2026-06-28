package opt;

import java.time.Duration;
import java.util.OptionalDouble;

public interface Solution {

    SolveStatus status();

    OptionalDouble objective();

    Duration runtime();

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

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

    default boolean isOptimal() {
        return status() == SolveStatus.OPTIMAL;
    }
}

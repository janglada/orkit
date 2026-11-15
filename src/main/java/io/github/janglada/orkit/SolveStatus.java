package io.github.janglada.orkit;

/**
 * Outcome of a solve attempt, mapped from the underlying OR-Tools solver status.
 * <p>
 * Use {@link Solution#status()} to obtain this value and the {@code is*} helpers on
 * {@link Solution} for common checks.
 */
public enum SolveStatus {
    /** The solver found a solution and proved no better objective exists. */
    OPTIMAL,
    /** The solver found a solution but did not prove it is globally optimal. */
    FEASIBLE,
    /** The model has no solution that satisfies all constraints. */
    INFEASIBLE,
    /** The objective is unbounded: solutions exist with arbitrarily good objective values. */
    UNBOUNDED,
    /** The solver stopped before reaching any conclusion (for example, a time limit was hit). */
    UNKNOWN,
    /** The model is malformed and could not be solved. */
    MODEL_INVALID
}

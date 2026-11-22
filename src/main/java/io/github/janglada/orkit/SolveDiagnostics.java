package io.github.janglada.orkit;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Search statistics reported alongside a {@link Solution}.
 * <p>
 * CP-SAT domains fill these in on every result, intermediate ones included. Routing
 * search is heuristic and proves no bound, so routing results carry {@link #none()}.
 *
 * @param bestBound            the best objective bound the solver proved: a lower bound
 *                             when minimizing, an upper bound when maximizing; empty when
 *                             the model has no objective or no solution was found
 * @param branches             search branches explored
 * @param conflicts            conflicts encountered during the search
 * @param infeasibilityReasons labels of constraint groups that together make the model
 *                             infeasible; filled only when infeasibility explanation was
 *                             requested and the model is {@link SolveStatus#INFEASIBLE}.
 *                             The set is sufficient, not necessarily minimal.
 */
public record SolveDiagnostics(OptionalDouble bestBound, long branches, long conflicts,
                               List<String> infeasibilityReasons) {

    private static final SolveDiagnostics NONE =
            new SolveDiagnostics(OptionalDouble.empty(), 0, 0, List.of());

    /** Copies the reason list and rejects nulls. */
    public SolveDiagnostics {
        if (bestBound == null) throw new ModelException("bestBound must not be null.");
        infeasibilityReasons = List.copyOf(infeasibilityReasons);
    }

    /** Diagnostics for a result that carries no search statistics. */
    public static SolveDiagnostics none() {
        return NONE;
    }

    /** A copy of these diagnostics with the given infeasibility reasons. */
    public SolveDiagnostics withInfeasibilityReasons(List<String> reasons) {
        return new SolveDiagnostics(bestBound, branches, conflicts, reasons);
    }
}

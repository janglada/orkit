package io.github.janglada.orkit;

import com.google.ortools.sat.CpSolverStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the root {@link SolveStatus} enum and {@link Solution} default
 * methods — covering status values that no real solve produces (UNKNOWN,
 * UNBOUNDED, MODEL_INVALID) so the truth table is pinned regardless of solver
 * reachability.
 */
class SolveStatusTest {

    // ── mapStatus: every CpSolverStatus maps to the documented SolveStatus ──

    @Test
    void mapStatusCoversAllCpSolverStatusValues() {
        assertThat(io.github.janglada.orkit.internal.CpSatParams.mapStatus(CpSolverStatus.OPTIMAL)).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(io.github.janglada.orkit.internal.CpSatParams.mapStatus(CpSolverStatus.FEASIBLE)).isEqualTo(SolveStatus.FEASIBLE);
        assertThat(io.github.janglada.orkit.internal.CpSatParams.mapStatus(CpSolverStatus.INFEASIBLE)).isEqualTo(SolveStatus.INFEASIBLE);
        assertThat(io.github.janglada.orkit.internal.CpSatParams.mapStatus(CpSolverStatus.MODEL_INVALID)).isEqualTo(SolveStatus.MODEL_INVALID);
        assertThat(io.github.janglada.orkit.internal.CpSatParams.mapStatus(CpSolverStatus.UNKNOWN)).isEqualTo(SolveStatus.UNKNOWN);
    }

    // ── isFeasible / isOptimal truth table across all six SolveStatus values ──

    @Test
    void optimalIsBothFeasibleAndOptimal() {
        Solution sol = sol(SolveStatus.OPTIMAL);
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.isOptimal()).isTrue();
    }

    @Test
    void feasibleIsFeasibleButNotOptimal() {
        Solution sol = sol(SolveStatus.FEASIBLE);
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.isOptimal()).isFalse();
    }

    @Test
    void nonSolutionStatusesAreNeitherFeasibleNorOptimal() {
        for (SolveStatus s : new SolveStatus[]{
                SolveStatus.INFEASIBLE,
                SolveStatus.UNBOUNDED,
                SolveStatus.UNKNOWN,
                SolveStatus.MODEL_INVALID}) {
            Solution sol = sol(s);
            assertThat(sol.isFeasible()).as("%s should not be feasible", s).isFalse();
            assertThat(sol.isOptimal()).as("%s should not be optimal", s).isFalse();
        }
    }

    /** Builds a minimal {@link Solution} for a given status, exercising the default {@code is*} methods. */
    private static Solution sol(SolveStatus status) {
        return new Solution() {
            @Override public SolveStatus status() { return status; }
            @Override public OptionalDouble objective() { return OptionalDouble.empty(); }
            @Override public Duration runtime() { return Duration.ZERO; }
        };
    }
}

package io.github.janglada.orkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link Solution#objective()} contract on non-solution outcomes:
 * when the solver cannot produce a solution (INFEASIBLE), the objective must
 * be empty ({@code OptionalDouble.empty()}) and {@code isFeasible()} false.
 * These assertions were missing from the per-domain INFEASIBLE tests.
 */
class SolutionContractTest {

    @Test
    void cuttingInfeasibleLeavesObjectiveEmpty() {
        // Piece longer than every stock roll => INFEASIBLE (pieces are required).
        var sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(50))
                .required("P", p -> p.length(80))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.status()).isEqualTo(SolveStatus.INFEASIBLE);
        assertThat(sol.isFeasible()).isFalse();
        assertThat(sol.objective()).isEmpty();
        assertThat(sol.runtime().isNegative()).isFalse();
    }

    @Test
    void routingInfeasibleLeavesObjectiveEmpty() {
        // Demand exceeds capacity with no dropping allowed => INFEASIBLE.
        var sol = Optimize.vehicleRouting()
                .depot("D", 41.35, 2.13)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(41.40, 2.20).demand(100))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.status()).isEqualTo(SolveStatus.INFEASIBLE);
        assertThat(sol.isFeasible()).isFalse();
        assertThat(sol.objective()).isEmpty();
    }

    @Test
    void schedulingInfeasibleLeavesObjectiveEmpty() {
        // Shift requires a skill no employee has => INFEASIBLE.
        var sol = Optimize.scheduling()
                .horizon(LocalDate.of(2026, 1, 5), 1)
                .employee("E", e -> e.skills("RN"))
                .shift("s", sh -> sh.on(LocalDate.of(2026, 1, 5))
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                        .needs(1).withSkill("SURGERY"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.status()).isEqualTo(SolveStatus.INFEASIBLE);
        assertThat(sol.isFeasible()).isFalse();
        assertThat(sol.objective()).isEmpty();
    }

    @Test
    void resultsBuiltWithoutDiagnosticsReportNone() {
        OptionalDouble obj = OptionalDouble.of(5);
        List<Solution> results = List.of(
                new io.github.janglada.orkit.jobshop.JobShopSchedule(SolveStatus.FEASIBLE, obj, Duration.ZERO,
                        5, List.of(), List.of(), Map.of()),
                new io.github.janglada.orkit.scheduling.EmployeeRoster(SolveStatus.FEASIBLE, obj, Duration.ZERO, List.of()),
                new io.github.janglada.orkit.knapsack.MultipleKnapsackSolution(SolveStatus.FEASIBLE, obj, Duration.ZERO,
                        5, List.of(), List.of()),
                new io.github.janglada.orkit.packing.PackingSolution(SolveStatus.FEASIBLE, obj, Duration.ZERO,
                        List.of(), List.of()),
                new io.github.janglada.orkit.booking.BookingSolution(SolveStatus.FEASIBLE, obj, Duration.ZERO,
                        List.of(), List.of()),
                new io.github.janglada.orkit.cutting.CuttingSolution(SolveStatus.FEASIBLE, obj, Duration.ZERO,
                        List.of(), List.of()));
        for (Solution s : results) {
            assertThat(s.diagnostics()).isSameAs(SolveDiagnostics.none());
            assertThat(s.bestBound()).isEmpty();
            assertThat(s.gap()).isEmpty();
        }
    }

    @Test
    void gapIsRelativeToTheObjective() {
        var diag = new SolveDiagnostics(OptionalDouble.of(90), 3, 1, List.of());
        Solution s = new io.github.janglada.orkit.packing.PackingSolution(SolveStatus.FEASIBLE,
                OptionalDouble.of(100), Duration.ZERO, List.of(), List.of(), diag);
        assertThat(s.gap().orElseThrow()).isEqualTo(0.1);
        // Objectives near zero divide by one, not by the objective.
        Solution tiny = new io.github.janglada.orkit.packing.PackingSolution(SolveStatus.FEASIBLE,
                OptionalDouble.of(0.5), Duration.ZERO, List.of(), List.of(), new SolveDiagnostics(OptionalDouble.of(0), 0, 0, List.of()));
        assertThat(tiny.gap().orElseThrow()).isEqualTo(0.5);
        assertThat(diag.withInfeasibilityReasons(List.of("x")).infeasibilityReasons()).containsExactly("x");
        assertThat(new io.github.janglada.orkit.packing.PackingSolution(SolveStatus.FEASIBLE, OptionalDouble.of(1),
                Duration.ZERO, List.of(), List.of(), null).diagnostics()).isSameAs(SolveDiagnostics.none());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SolveDiagnostics(null, 0, 0, List.of()))
                .isInstanceOf(ModelException.class);
    }
}

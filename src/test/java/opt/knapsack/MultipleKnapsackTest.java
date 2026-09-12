package opt.knapsack;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipleKnapsackTest {

    // ── T-Kn1: Known-optimal instance — total profit 31, the low-value item is dropped ──
    // Two bins (cap 10 each). Items (profit,weight): I1(10,6) I2(8,5) I3(6,5) I4(7,4) I5(3,3)
    // Total weight 23 > total capacity 20 → must drop ≥ 3 weight. The cheapest drop is I5
    // (profit 3). Remaining {I1,I2,I3,I4} packs as {6,4}+{5,5} → profit 31.

    @Test
    void knownOptimalPacksProfit31AndDropsLowValueItem() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(10).weight(6))
                .item("I2", i -> i.profit(8).weight(5))
                .item("I3", i -> i.profit(6).weight(5))
                .item("I4", i -> i.profit(7).weight(4))
                .item("I5", i -> i.profit(3).weight(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalProfit()).isEqualTo(31);
        assertThat(sol.unassignedItems()).containsExactly("I5");

        assertCapacityRespected(sol.assignments());
        assertEachItemInAtMostOneBin(sol);
    }

    // ── T-Kn2: Single bin collapses to classic 0/1 knapsack (greedy-by-ratio fails) ──
    // cap 50, items (60,10) (100,20) (120,30). Greedy ratio picks 60+100=160; optimal
    // picks 100+120=220.

    @Test
    void singleBinIsClassicZeroOneKnapsackOptimal220() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(50))
                .item("A", i -> i.profit(60).weight(10))
                .item("B", i -> i.profit(100).weight(20))
                .item("C", i -> i.profit(120).weight(30))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalProfit()).isEqualTo(220);
        assertThat(sol.unassignedItems()).containsExactly("A");
        assertCapacityRespected(sol.assignments());
    }

    // ── T-Kn3: Item heavier than every bin is left unassigned ──────────────────────

    @Test
    void overweightItemIsUnassigned() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("heavy", i -> i.profit(100).weight(11))   // > both bins
                .item("light", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalProfit()).isEqualTo(5);
        assertThat(sol.unassignedItems()).containsExactly("heavy");
    }

    // ── T-Kn4: Everything fits trivially → all profit captured ─────────────────────

    @Test
    void everythingFitsCapturesAllProfit() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(5).weight(3))
                .item("I2", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalProfit()).isEqualTo(10);
        assertThat(sol.unassignedItems()).isEmpty();
    }

    // ── T-Kn5: binOf() / assignmentOf() accessor round-trip ────────────────────────

    @Test
    void binOfAndAssignmentOfAreConsistent() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(7).weight(3))
                .item("I2", i -> i.profit(9).weight(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.isFeasible()).isTrue();

        for (String item : List.of("I1", "I2")) {
            String bin = sol.binOf(item).orElseThrow();
            BinAssignment a = sol.assignmentOf(bin).orElseThrow();
            assertThat(a.items()).contains(item);
        }
    }

    // ── T-Kn6: Duplicate bin id throws ModelException ──────────────────────────────

    @Test
    void duplicateBinIdThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .bin("B1", b -> b.capacity(10))
                        .bin("B1", b -> b.capacity(5))
                        .item("I1", i -> i.profit(1).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("B1");
    }

    // ── T-Kn7: capacity(0) / weight(0) / profit(0) throw ModelException ────────────

    @Test
    void zeroCapacityWeightProfitThrowModelException() {
        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .bin("B1", b -> b.capacity(0))
                        .item("I1", i -> i.profit(1).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("capacity");

        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .bin("B1", b -> b.capacity(10))
                        .item("I1", i -> i.profit(1).weight(0))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("weight");

        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .bin("B1", b -> b.capacity(10))
                        .item("I1", i -> i.profit(0).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("profit");
    }

    // ── T-Kn8: Empty model (no items / no bins) throws ModelException ──────────────

    @Test
    void emptyModelThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .bin("B1", b -> b.capacity(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("item");

        assertThatThrownBy(() ->
                Optimize.multipleKnapsack()
                        .item("I1", i -> i.profit(1).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("bin");
    }

    // ── T-Kn9: Reusable problem solved twice with different time limits ────────────

    @Test
    void reusableProblemGivesSameOptimalProfit() {
        var problem = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(10).weight(6))
                .item("I2", i -> i.profit(8).weight(5))
                .item("I3", i -> i.profit(6).weight(5))
                .item("I4", i -> i.profit(7).weight(4))
                .item("I5", i -> i.profit(3).weight(3))
                .build();

        MultipleKnapsackSolution fast = problem.solver().timeLimit(Duration.ofSeconds(3)).solve();
        MultipleKnapsackSolution tight = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(fast.isFeasible()).isTrue();
        assertThat(tight.isFeasible()).isTrue();
        assertThat(tight.totalProfit()).isLessThanOrEqualTo(fast.totalProfit() + 0);
        // Both reach the known optimum of 31
        assertThat(fast.totalProfit()).isEqualTo(31);
        assertThat(tight.totalProfit()).isEqualTo(31);
    }

    // ── T-Kn10: runtime() is non-negative ──────────────────────────────────────────

    @Test
    void runtimeIsNonNegative() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .item("I1", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.runtime().isNegative()).isFalse();
    }

    // ── T-Kn11: Structural — per-bin profit sums to total; capacity respected ──────

    @Test
    void perBinProfitsSumToTotalAndCapacityRespected() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .bin("B3", b -> b.capacity(10))
                .item("I1", i -> i.profit(10).weight(6))
                .item("I2", i -> i.profit(8).weight(5))
                .item("I3", i -> i.profit(6).weight(5))
                .item("I4", i -> i.profit(7).weight(4))
                .item("I5", i -> i.profit(3).weight(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();

        long sumProfit = sol.assignments().stream().mapToLong(BinAssignment::profit).sum();
        assertThat(sumProfit).isEqualTo(sol.totalProfit());

        assertCapacityRespected(sol.assignments());
        assertEachItemInAtMostOneBin(sol);
    }

    // ── T-Kn12: Medium instance stays feasible within the time limit ───────────────

    @Test
    void mediumInstanceSolvesFeasiblyWithinTimeLimit() {
        // 15 items, 3 bins — a non-trivial assignment problem
        int[] profits = {10, 12, 8, 14, 9, 11, 7, 13, 6, 15, 5, 10, 8, 9, 11};
        int[] weights = {4, 6, 3, 7, 5, 5, 4, 6, 3, 8, 2, 5, 4, 4, 6};

        var builder = Optimize.multipleKnapsack()
                .bin("K1", b -> b.capacity(20))
                .bin("K2", b -> b.capacity(20))
                .bin("K3", b -> b.capacity(20));
        for (int i = 0; i < profits.length; i++) {
            final int idx = i;
            builder.item("I" + idx, it -> it.profit(profits[idx]).weight(weights[idx]));
        }

        MultipleKnapsackSolution sol = builder.solver()
                .timeLimit(Duration.ofSeconds(10))
                .workers(8)
                .solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.totalProfit()).isPositive();
        assertCapacityRespected(sol.assignments());
        assertEachItemInAtMostOneBin(sol);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void assertCapacityRespected(List<BinAssignment> assignments) {
        for (BinAssignment a : assignments) {
            assertThat(a.usedCapacity())
                    .as("bin %s uses %d but capacity is %d", a.bin(), a.usedCapacity(), a.capacity())
                    .isLessThanOrEqualTo(a.capacity());
        }
    }

    private void assertEachItemInAtMostOneBin(MultipleKnapsackSolution sol) {
        Set<String> seen = new HashSet<>();
        for (BinAssignment a : sol.assignments()) {
            for (String item : a.items()) {
                assertThat(seen.add(item))
                        .as("item %s appears in more than one bin", item)
                        .isTrue();
            }
        }
    }

    // ── T-Kn13: Duplicate item id is rejected ───────────────────────────────

    @Test
    void duplicateItemIdThrowsModelException() {
        assertThatThrownBy(() -> Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("I", i -> i.profit(5).weight(3))
                .item("I", i -> i.profit(4).weight(2))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("Duplicate item id");
    }

    // ── T-Kn14: Negative capacity / profit / weight ─────────────────────────

    @Test
    void negativeValuesThrowModelException() {
        assertThatThrownBy(() -> Optimize.multipleKnapsack().bin("B", b -> b.capacity(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("I", i -> i.profit(-1).weight(3))).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("I", i -> i.profit(5).weight(-1))).isInstanceOf(ModelException.class);
    }

    // ── T-Kn15: Blank bin/item ids are rejected ─────────────────────────────

    @Test
    void blankIdsThrowModelException() {
        assertThatThrownBy(() -> Optimize.multipleKnapsack()
                .bin("", b -> b.capacity(10)))
                .isInstanceOf(ModelException.class).hasMessageContaining("bin id");
        assertThatThrownBy(() -> Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("", i -> i.profit(5).weight(3)))
                .isInstanceOf(ModelException.class).hasMessageContaining("item id");
    }

    // ── T-Kn16: Unknown accessors return empty ──────────────────────────────

    @Test
    void unknownAccessorsReturnEmpty() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("I", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.binOf("NOPE")).isEmpty();
        assertThat(sol.assignmentOf("NOPE")).isEmpty();
    }

    // ── T-Kn17: Empty declared bin appears in assignments() ─────────────────

    @Test
    void emptyBinAppearsInAssignments() {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10)) // empty
                .item("I", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.assignments()).hasSize(2);
        BinAssignment empty = sol.assignments().stream()
                .filter(a -> a.items().isEmpty()).findFirst().orElseThrow();
        assertThat(empty.usedCapacity()).isZero();
        assertThat(empty.profit()).isZero();
    }

    // ── T-Kn18: Re-solve yields the same profit and objective ───────────────

    @Test
    void reusableBuildYieldsSameProfitAndObjective() {
        var problem = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(5).weight(4))
                .item("I2", i -> i.profit(6).weight(5))
                .item("I3", i -> i.profit(3).weight(2))
                .build();
        MultipleKnapsackSolution s1 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        MultipleKnapsackSolution s2 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(s1.totalProfit()).isEqualTo(s2.totalProfit());
        assertThat(s1.objective()).isEqualTo(s2.objective());
    }
}

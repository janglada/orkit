package opt.cutting;

import opt.ModelException;
import opt.SolveStatus;
import opt.cutting.StockAssignment.CutPiece;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CuttingTest {

    // ── T-Ct1: Exact fit produces zero waste ────────────────────────────────────

    @Test
    void exactFit() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100))
                .required("A", p -> p.length(40))
                .required("B", p -> p.length(60))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalWaste()).isZero();
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        assertThat(sol.unplacedPieces()).isEmpty();
    }

    // ── T-Ct2: Waste is computed as roll length minus cut length ────────────────

    @Test
    void wasteCalculated() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100))
                .required("A", p -> p.length(30))
                .required("B", p -> p.length(40))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalWaste()).isEqualTo(30);
        assertThat(sol.totalStockUsed()).isEqualTo(1);
    }

    // ── T-Ct3: Pieces exceeding one roll require a second roll ───────────────────

    @Test
    void twoRollsNeeded() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100).quantity(2))
                .required("A", p -> p.length(90))
                .required("B", p -> p.length(60))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(2);
        assertThat(sol.unplacedPieces()).isEmpty();
    }

    // ── T-Ct4: MinimizeWaste vs MinimizeStockUsed yield different selections ────
    // Rolls: two 60-length rolls (R1#1, R1#2) and one 200-length roll (R2).
    // Pieces: two 50-length pieces (total 100).
    // MinimizeWaste prefers R1#1 + R1#2 (total length 120, waste 20) over R2 (length 200, waste 100).
    // MinimizeStockUsed prefers R2 alone (1 roll) over R1#1 + R1#2 (2 rolls).

    @Test
    void minimizeWasteVsStockUsed() {
        var wasteProblem = new CuttingBuilder()
                .stock("R1", s -> s.length(60).quantity(2))
                .stock("R2", s -> s.length(200))
                .required("P", p -> p.length(50).quantity(2))
                .minimize(Goals.minimizeWaste())
                .build();

        CuttingSolution wasteSol = wasteProblem.solver().timeLimit(Duration.ofSeconds(10)).solve();

        var stockProblem = new CuttingBuilder()
                .stock("R1", s -> s.length(60).quantity(2))
                .stock("R2", s -> s.length(200))
                .required("P", p -> p.length(50).quantity(2))
                .minimize(Goals.minimizeStockUsed())
                .build();

        CuttingSolution stockSol = stockProblem.solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(wasteSol.isFeasible()).isTrue();
        assertThat(stockSol.isFeasible()).isTrue();

        // MinimizeWaste: 2 short rolls used, total length consumed 120, waste 20.
        assertThat(wasteSol.totalStockUsed()).isEqualTo(2);
        assertThat(wasteSol.totalWaste()).isEqualTo(20);

        // MinimizeStockUsed: 1 roll (the long R2) used, waste 100.
        assertThat(stockSol.totalStockUsed()).isEqualTo(1);
        assertThat(stockSol.totalWaste()).isEqualTo(100);
    }

    // ── T-Ct5: Piece quantity expands into individual instances ─────────────────

    @Test
    void quantityExpansion() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(200))
                .required("P", p -> p.length(50).quantity(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.unplacedPieces()).isEmpty();

        List<CutPiece> allCuts = sol.assignments().stream()
                .flatMap(a -> a.pieces().stream())
                .toList();
        assertThat(allCuts).hasSize(3);
        assertThat(allCuts).allMatch(c -> c.length() == 50);
        assertNoOverlap1D(sol.assignments());
    }

    // ── T-Ct6: Stock quantity makes multiple rolls available ────────────────────

    @Test
    void stockQuantity() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100).quantity(2))
                .required("A", p -> p.length(90))
                .required("B", p -> p.length(90))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(2);
        // Two declared rolls appear in assignments.
        assertThat(sol.assignments()).hasSize(2);
    }

    // ── T-Ct7: A piece longer than every roll is infeasible ─────────────────────

    @Test
    void infeasibleWhenTooLong() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(50))
                .required("A", p -> p.length(80))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.isFeasible()).isFalse();
        assertThat(sol.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-Ct8: Per-roll cut pieces are pairwise non-overlapping ─────────────────

    @Test
    void noOverlapStructural() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100).quantity(2))
                .required("A", p -> p.length(30))
                .required("B", p -> p.length(40))
                .required("C", p -> p.length(50))
                .required("D", p -> p.length(25))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertNoOverlap1D(sol.assignments());

        // Each piece assigned to exactly one roll, none left over.
        Set<String> placed = new HashSet<>();
        for (StockAssignment a : sol.assignments()) {
            for (CutPiece c : a.pieces()) {
                assertThat(placed.add(c.pieceId()))
                        .as("piece %s appears on more than one roll", c.pieceId())
                        .isTrue();
            }
        }
        assertThat(placed).hasSize(4);
        assertThat(sol.unplacedPieces()).isEmpty();
    }

    // ── T-Ct9: Duplicate stock id throws ModelException ─────────────────────────

    @Test
    void duplicateStockId() {
        assertThatThrownBy(() ->
                new CuttingBuilder()
                        .stock("R", s -> s.length(100))
                        .stock("R", s -> s.length(50))
                        .required("A", p -> p.length(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("R");
    }

    // ── T-Ct10: Zero length throws ModelException ───────────────────────────────

    @Test
    void zeroLength() {
        assertThatThrownBy(() ->
                new CuttingBuilder()
                        .stock("R", s -> s.length(0))
                        .required("A", p -> p.length(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("length");

        assertThatThrownBy(() ->
                new CuttingBuilder()
                        .stock("R", s -> s.length(100))
                        .required("A", p -> p.length(0))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("length");
    }

    // ── T-Ct11: A built problem can be solved twice ─────────────────────────────

    @Test
    void reusableBuild() {
        var problem = new CuttingBuilder()
                .stock("R", s -> s.length(100).quantity(2))
                .required("A", p -> p.length(70))
                .required("B", p -> p.length(60))
                .build();

        CuttingSolution first = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        CuttingSolution second = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(first.isFeasible()).isTrue();
        assertThat(second.isFeasible()).isTrue();
        assertThat(first.totalStockUsed()).isEqualTo(2);
        assertThat(second.totalStockUsed()).isEqualTo(2);
        assertThat(first.totalWaste()).isEqualTo(second.totalWaste());
    }

    // ── T-Ct12: No stocks throws ModelException ─────────────────────────────────

    @Test
    void noStocks() {
        assertThatThrownBy(() ->
                new CuttingBuilder()
                        .required("A", p -> p.length(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("stock");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void assertNoOverlap1D(List<StockAssignment> assignments) {
        for (StockAssignment a : assignments) {
            List<CutPiece> cuts = new ArrayList<>(a.pieces());
            for (int i = 0; i < cuts.size(); i++) {
                for (int j = i + 1; j < cuts.size(); j++) {
                    CutPiece c1 = cuts.get(i);
                    CutPiece c2 = cuts.get(j);
                    long end1 = c1.start() + c1.length();
                    long end2 = c2.start() + c2.length();
                    boolean disjoint = end1 <= c2.start() || end2 <= c1.start();
                    assertThat(disjoint)
                            .as("pieces %s and %s overlap on roll %s", c1.pieceId(), c2.pieceId(), a.stockId())
                            .isTrue();
                }
            }
            // Cuts must also fit within the roll.
            for (CutPiece c : cuts) {
                assertThat(c.start() + c.length())
                        .as("piece %s exceeds roll %s length", c.pieceId(), a.stockId())
                        .isLessThanOrEqualTo(a.length());
            }
        }
    }
}

package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.cutting.StockAssignment.CutPiece;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
                .goal(Goals.minimizeWaste())
                .build();

        CuttingSolution wasteSol = wasteProblem.solver().timeLimit(Duration.ofSeconds(10)).solve();

        var stockProblem = new CuttingBuilder()
                .stock("R1", s -> s.length(60).quantity(2))
                .stock("R2", s -> s.length(200))
                .required("P", p -> p.length(50).quantity(2))
                .goal(Goals.minimizeStockUsed())
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

    // ── 2D helpers ─────────────────────────────────────────────────────────────

    private void assertNoOverlap2D(List<StockAssignment> assignments) {
        for (StockAssignment a : assignments) {
            List<CutPiece> cuts = new ArrayList<>(a.pieces());
            for (int i = 0; i < cuts.size(); i++) {
                for (int j = i + 1; j < cuts.size(); j++) {
                    CutPiece c1 = cuts.get(i);
                    CutPiece c2 = cuts.get(j);
                    long xEnd1 = c1.x() + c1.length();
                    long xEnd2 = c2.x() + c2.length();
                    long yEnd1 = c1.y() + c1.width();
                    long yEnd2 = c2.y() + c2.width();
                    boolean xSeparated = xEnd1 <= c2.x() || xEnd2 <= c1.x();
                    boolean ySeparated = yEnd1 <= c2.y() || yEnd2 <= c1.y();
                    assertThat(xSeparated || ySeparated)
                            .as("pieces %s and %s overlap on sheet %s", c1.pieceId(), c2.pieceId(), a.stockId())
                            .isTrue();
                }
            }
        }
    }

    private void assertWithinSheetBounds(List<StockAssignment> assignments) {
        for (StockAssignment a : assignments) {
            for (CutPiece c : a.pieces()) {
                assertThat(c.x() + c.length())
                        .as("piece %s exceeds sheet %s length", c.pieceId(), a.stockId())
                        .isLessThanOrEqualTo(a.length());
                assertThat(c.y() + c.width())
                        .as("piece %s exceeds sheet %s width", c.pieceId(), a.stockId())
                        .isLessThanOrEqualTo(a.width());
            }
        }
    }

    // ── T-Ct13: No pieces / duplicate piece id / null goal ──────────────────

    @Test
    void noPiecesDuplicatePieceAndNullGoalThrowModelException() {
        assertThatThrownBy(() -> new CuttingBuilder().stock("S", s -> s.length(100)).build())
                .isInstanceOf(ModelException.class).hasMessageContaining("piece");
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100))
                .required("P", p -> p.length(40))
                .required("P", p -> p.length(30))
                .build()).isInstanceOf(ModelException.class).hasMessageContaining("Duplicate piece id");
        assertThatThrownBy(() -> new CuttingBuilder().goal(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("goal");
    }

    // ── T-Ct14: cost / quantity / length boundaries ─────────────────────────

    @Test
    void zeroOrNegativeCostQuantityLengthThrowModelException() {
        assertThatThrownBy(() -> new CuttingBuilder().stock("S", s -> s.length(100).cost(0)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new CuttingBuilder().stock("S", s -> s.length(100).cost(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new CuttingBuilder().stock("S", s -> s.length(100).quantity(0)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100))
                .required("P", p -> p.length(40).quantity(-1))).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new CuttingBuilder().stock("S", s -> s.length(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100))
                .required("P", p -> p.length(-1))).isInstanceOf(ModelException.class);
    }

    // ── T-Ct15: Blank stock/piece ids are rejected ──────────────────────────

    @Test
    void blankIdsThrowModelException() {
        assertThatThrownBy(() -> new CuttingBuilder().stock("", s -> s.length(100)))
                .isInstanceOf(ModelException.class).hasMessageContaining("stock id");
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100))
                .required("", p -> p.length(40))).isInstanceOf(ModelException.class).hasMessageContaining("piece id");
    }

    // ── T-Ct16: Exact-fit boundary (piece length == stock length) ───────────

    @Test
    void exactFitSinglePieceEqualsRollLength() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("S", s -> s.length(50))
                .required("P", p -> p.length(50))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        assertThat(sol.totalWaste()).isEqualTo(0);
    }

    // ── T-Ct17: Instance ids keep the declared id: "<id>#<n>" ───────────────

    @Test
    void instanceIdsKeepDeclaredIdWithCounter() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("S", s -> s.length(100))
                .required("P", p -> p.length(40))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        StockAssignment roll = sol.assignments().stream()
                .filter(a -> !a.pieces().isEmpty()).findFirst().orElseThrow();
        assertThat(roll.stockId()).isEqualTo("S#1");
        assertThat(roll.stockType()).isEqualTo("S");
        assertThat(roll.pieces().get(0).pieceId()).isEqualTo("P#1");
        assertThat(roll.pieces().get(0).pieceType()).isEqualTo("P");
    }

    // ── T-Ct18: MinimizeTotalCost prefers cheaper stock ────────────────────
    // Two stock types of identical length but different cost. Both MinimizeWaste
    // and MinimizeStockUsed are indifferent between them; MinimizeTotalCost
    // must select the cheaper one.

    @Test
    void minimizeTotalCostPrefersCheaperStock() {
        // Two 100mm stocks: cheap costs 1, expensive costs 100.
        // One 50mm piece: fits on either roll.
        CuttingSolution sol = new CuttingBuilder()
                .stock("cheap", s -> s.length(100).cost(1))
                .stock("expensive", s -> s.length(100).cost(100))
                .required("P", p -> p.length(50))
                .goal(Goals.minimizeTotalCost())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        // The piece must be on the cheap roll (cost=1).
        StockAssignment used = sol.assignments().stream()
                .filter(a -> !a.pieces().isEmpty()).findFirst().orElseThrow();
        assertThat(used.cost()).isEqualTo(1);
        // Objective ≈ 1.0 (cost of cheap roll).
        assertThat(sol.objective()).isPresent();
        assertThat(sol.objective().getAsDouble()).isCloseTo(1.0, within(1e-9));
    }

    // ── T-Ct19: allowDroppingPieces makes oversized piece feasible ──────────

    @Test
    void allowDroppingPiecesMakesOversizedPieceFeasible() {
        // Without dropping, a piece too long for any roll is INFEASIBLE.
        CuttingSolution infeasible = new CuttingBuilder()
                .stock("R", s -> s.length(50))
                .required("big", p -> p.length(80))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(infeasible.status()).isEqualTo(SolveStatus.INFEASIBLE);

        // With dropping enabled, the piece is dropped and the model is FEASIBLE.
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(50))
                .required("big", p -> p.length(80))
                .allowDroppingPieces(5)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unplacedPieces()).containsExactly("big#1");
        assertThat(sol.totalStockUsed()).isZero();
    }

    // ── T-Ct20: allowDroppingPieces — oversized piece dropped, model feasible ─

    @Test
    void allowDroppingPiecesOversizedDroppedWithPenalty() {
        // One 100mm roll, two pieces: one fits (50mm), one is too long (150mm).
        // With dropping enabled, the oversized piece is dropped and the model
        // stays feasible. The objective includes the penalty.
        CuttingSolution sol = new CuttingBuilder()
                .stock("R", s -> s.length(100))
                .required("short", p -> p.length(50))
                .required("long", p -> p.length(150))
                .allowDroppingPieces(50)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        // The oversized piece is impossible to place → dropped.
        assertThat(sol.unplacedPieces()).contains("long#1");
        // The short piece is placed.
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        // Objective ≈ 1 stock + 50 penalty for the dropped piece.
        assertThat(sol.objective()).isPresent();
        assertThat(sol.objective().getAsDouble()).isCloseTo(51.0, within(1e-9));
    }

    // ── T-Ct21: 2D exact-fit boundary (piece length×width == sheet length×width) ──

    @Test
    void twoDimensionalExactFit() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("S", s -> s.length(10).width(10))
                .required("P", p -> p.length(10).width(10))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        assertThat(sol.totalAreaWaste()).isZero();
        assertThat(sol.unplacedPieces()).isEmpty();

        StockAssignment sheet = sol.assignments().stream()
                .filter(a -> !a.pieces().isEmpty()).findFirst().orElseThrow();
        assertThat(sheet.width()).isEqualTo(10);
        CutPiece piece = sheet.pieces().get(0);
        assertThat(piece.x()).isZero();
        assertThat(piece.y()).isZero();
        assertThat(piece.width()).isEqualTo(10);
    }

    // ── T-Ct22: 2D no-overlap + within-bounds (four 10×10 pieces on a 20×20 sheet) ──

    @Test
    void twoDimensionalNoOverlapAndWithinBounds() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("S", s -> s.length(20).width(20))
                .required("Q", p -> p.length(10).width(10).quantity(4))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        assertThat(sol.totalAreaWaste()).isZero();
        assertThat(sol.unplacedPieces()).isEmpty();
        assertNoOverlap2D(sol.assignments());
        assertWithinSheetBounds(sol.assignments());

        // All four pieces placed on the one sheet.
        long placed = sol.assignments().stream()
                .flatMap(a -> a.pieces().stream()).count();
        assertThat(placed).isEqualTo(4);
    }

    // ── T-Ct23: MinimizeWaste (2D = area) vs MinimizeStockUsed selection difference ──
    // Sheets: two 10×10 R1 sheets (area 100 each) and one 20×20 R2 sheet (area 400).
    // Pieces: two 10×10 pieces (total area 200).
    // MinimizeWaste prefers two R1 sheets (total area 200, waste 0) over one R2
    // (area 400, waste 200). MinimizeStockUsed prefers the single R2 sheet.

    @Test
    void twoDimensionalMinimizeWasteVsStockUsed() {
        var wasteProblem = new CuttingBuilder()
                .stock("R1", s -> s.length(10).width(10).quantity(2))
                .stock("R2", s -> s.length(20).width(20))
                .required("P", p -> p.length(10).width(10).quantity(2))
                .goal(Goals.minimizeWaste())
                .build();

        CuttingSolution wasteSol = wasteProblem.solver().timeLimit(Duration.ofSeconds(10)).solve();

        var stockProblem = new CuttingBuilder()
                .stock("R1", s -> s.length(10).width(10).quantity(2))
                .stock("R2", s -> s.length(20).width(20))
                .required("P", p -> p.length(10).width(10).quantity(2))
                .goal(Goals.minimizeStockUsed())
                .build();

        CuttingSolution stockSol = stockProblem.solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(wasteSol.isFeasible()).isTrue();
        assertThat(stockSol.isFeasible()).isTrue();

        // MinimizeWaste (area): 2 small sheets used, total area consumed 200, waste 0.
        assertThat(wasteSol.totalStockUsed()).isEqualTo(2);
        assertThat(wasteSol.totalAreaWaste()).isZero();
        assertNoOverlap2D(wasteSol.assignments());
        assertWithinSheetBounds(wasteSol.assignments());

        // MinimizeStockUsed: 1 sheet (the large R2) used, area waste 200.
        assertThat(stockSol.totalStockUsed()).isEqualTo(1);
        assertThat(stockSol.totalAreaWaste()).isEqualTo(200);
        assertNoOverlap2D(stockSol.assignments());
        assertWithinSheetBounds(stockSol.assignments());
    }

    // ── T-Ct24: negative width → ModelException (">= 1") ──────────────────────

    @Test
    void negativeWidthThrowsModelException() {
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100).width(-1))
                .required("P", p -> p.length(10).width(5))
                .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining(">= 1");

        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100).width(5))
                .required("P", p -> p.length(10).width(-1))
                .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining(">= 1");
    }

    // ── T-Ct25: mixed 1D/2D (some pieces with width, some without) → ModelException ──

    @Test
    void mixedOneAndTwoDimensionalThrowsModelException() {
        // A piece without width while another stock/piece is 2D.
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100).width(50))
                .required("A", p -> p.length(10).width(5))
                .required("B", p -> p.length(20))  // no width → mixed
                .solver().timeLimit(Duration.ofSeconds(5)).solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("2D cutting requires every stock and piece to declare a width");

        // A stock without width while pieces are 2D.
        assertThatThrownBy(() -> new CuttingBuilder()
                .stock("S", s -> s.length(100))  // no width
                .required("A", p -> p.length(10).width(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("2D cutting requires every stock and piece to declare a width");
    }

    // ── T-Ct26: guillotine layouts are edge-to-edge cuttable ───────────────────

    @Test
    void guillotineLayoutsAreEdgeToEdgeCuttable() {
        CuttingSolution sol = new CuttingBuilder()
                .stock("S", s -> s.length(20).width(12).quantity(3))
                .required("A", p -> p.length(7).width(5).quantity(4))
                .required("B", p -> p.length(6).width(3).quantity(5))
                .guillotine(true)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unplacedPieces()).isEmpty();
        CuttingCapabilitiesTest.assertValid2D(sol, 0);
        for (StockAssignment a : sol.assignments()) {
            assertThat(CuttingCapabilitiesTest.isGuillotine(a.pieces(), 0, 0, a.length(), a.width())).isTrue();
        }
    }
}

package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.cutting.StockAssignment.CutPiece;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scale of the count-based 1D model, 2D rotation, kerf, and two-stage guillotine layouts. */
class CuttingCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    // ── Validators ──────────────────────────────────────────────────────────

    /** Pieces lie inside their sheet and, pairwise, are at least {@code kerf} apart on some axis. */
    static void assertValid2D(CuttingSolution sol, long kerf) {
        for (StockAssignment a : sol.assignments()) {
            List<CutPiece> cuts = a.pieces();
            for (CutPiece c : cuts) {
                assertThat(c.x()).isNotNegative();
                assertThat(c.y()).isNotNegative();
                assertThat(c.x() + c.length()).as("%s inside %s", c.pieceId(), a.stockId()).isLessThanOrEqualTo(a.length());
                assertThat(c.y() + c.width()).as("%s inside %s", c.pieceId(), a.stockId()).isLessThanOrEqualTo(a.width());
            }
            for (int i = 0; i < cuts.size(); i++) {
                for (int j = i + 1; j < cuts.size(); j++) {
                    CutPiece p = cuts.get(i), q = cuts.get(j);
                    boolean apart = p.x() + p.length() + kerf <= q.x() || q.x() + q.length() + kerf <= p.x()
                            || p.y() + p.width() + kerf <= q.y() || q.y() + q.width() + kerf <= p.y();
                    assertThat(apart).as("%s and %s on %s overlap or violate kerf", p.pieceId(), q.pieceId(), a.stockId()).isTrue();
                }
            }
            long area = cuts.stream().mapToLong(c -> c.length() * c.width()).sum();
            assertThat(a.waste()).isEqualTo(cuts.isEmpty() ? 0 : a.length() * a.width() - area);
        }
    }

    /** A layout is guillotine if it can be split by edge-to-edge cuts until each part holds at most one piece. */
    static boolean isGuillotine(List<CutPiece> cuts, long x0, long y0, long x1, long y1) {
        if (cuts.size() <= 1) return true;
        for (boolean vertical : new boolean[]{true, false}) {
            List<Long> candidates = new ArrayList<>();
            for (CutPiece c : cuts) candidates.add(vertical ? c.x() + c.length() : c.y() + c.width());
            for (long at : candidates) {
                if (at <= (vertical ? x0 : y0) || at >= (vertical ? x1 : y1)) continue;
                List<CutPiece> before = new ArrayList<>();
                List<CutPiece> after = new ArrayList<>();
                boolean straddles = false;
                for (CutPiece c : cuts) {
                    long lo = vertical ? c.x() : c.y();
                    long hi = vertical ? c.x() + c.length() : c.y() + c.width();
                    if (hi <= at) before.add(c);
                    else if (lo >= at) after.add(c);
                    else straddles = true;
                }
                if (straddles || before.isEmpty() || after.isEmpty()) continue;
                boolean ok = vertical
                        ? isGuillotine(before, x0, y0, at, y1) && isGuillotine(after, at, y0, x1, y1)
                        : isGuillotine(before, x0, y0, x1, at) && isGuillotine(after, x0, at, x1, y1);
                if (ok) return true;
            }
        }
        return false;
    }

    // ── 1D scale and kerf ───────────────────────────────────────────────────

    private static CuttingBuilder rolls(int perType, int rolls) {
        return Optimize.cuttingStock().stock("R", k -> k.length(6000).cost(1).quantity(rolls))
                .required("A", p -> p.length(1500).quantity(perType))
                .required("B", p -> p.length(1150).quantity(perType))
                .required("C", p -> p.length(870).quantity(perType))
                .required("D", p -> p.length(2300).quantity(perType))
                .goal(Goals.minimizeStockUsed());
    }

    @Test
    void countModelProvesSmallInstancesOptimal() {
        CuttingSolution sol = rolls(10, 12).solver().timeLimit(LIMIT).solve();
        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.totalStockUsed()).isEqualTo(10);
        assertThat(sol.unplacedPieces()).isEmpty();
        // Every instance id appears exactly once.
        List<String> ids = sol.assignments().stream().flatMap(a -> a.pieces().stream()).map(CutPiece::pieceId).toList();
        assertThat(ids).hasSize(40).doesNotHaveDuplicates().contains("A#1", "A#10", "D#10");
    }

    @Test
    void countModelReachesTheLowerBoundOnLargerInstances() {
        // 30 pieces of each type need ceil(30 * 5820 / 6000) = 30 rolls.
        CuttingSolution sol = rolls(30, 35).solver().timeLimit(LIMIT).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.totalStockUsed()).isEqualTo(30);
        for (StockAssignment a : sol.assignments()) {
            long used = a.pieces().stream().mapToLong(CutPiece::length).sum();
            assertThat(used).isLessThanOrEqualTo(a.length());
        }
    }

    @Test
    void kerfSeparatesPiecesOnARoll() {
        CuttingSolution tight = Optimize.cuttingStock()
                .stock("R", s -> s.length(100).quantity(2))
                .required("P", p -> p.length(50).quantity(2))
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(tight.totalStockUsed()).isEqualTo(1);

        CuttingSolution withKerf = Optimize.cuttingStock()
                .stock("R", s -> s.length(101).quantity(2))
                .required("P", p -> p.length(50).quantity(2))
                .kerf(2)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(withKerf.totalStockUsed()).as("50 + 2 + 50 > 101").isEqualTo(2);

        CuttingSolution fits = Optimize.cuttingStock()
                .stock("R", s -> s.length(102).quantity(2))
                .required("P", p -> p.length(50).quantity(2))
                .kerf(2)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(fits.totalStockUsed()).isEqualTo(1);
        StockAssignment roll = fits.assignments().stream().filter(a -> !a.pieces().isEmpty()).findFirst().orElseThrow();
        assertThat(roll.pieces()).extracting(CutPiece::x).containsExactly(0L, 52L);
        assertThat(roll.waste()).as("kerf loss counts as waste").isEqualTo(2);
    }

    @Test
    void droppedPiecesAreTheHighestNumberedInstances() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("R", s -> s.length(100))
                .required("P", p -> p.length(40).quantity(3))
                .allowDroppingPieces(1000)
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.unplacedPieces()).containsExactly("P#3");
    }

    @Test
    void warmStartAcceptsSolutionsFromAnotherModel() {
        CuttingSolution previous = rolls(4, 6).solver().timeLimit(LIMIT).solve();
        CuttingSolution sol = rolls(10, 12).solver().timeLimit(LIMIT).warmStart(previous).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.totalStockUsed()).isEqualTo(10);
    }

    // ── 2D rotation and kerf ────────────────────────────────────────────────

    @Test
    void rotationLetsAPieceFitOnlyWhenTurned() {
        CuttingSolution fixed = Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(4))
                .required("P", p -> p.length(3).width(8))
                .solver().timeLimit(LIMIT).solve();
        assertThat(fixed.status()).isEqualTo(SolveStatus.INFEASIBLE);

        CuttingSolution turned = Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(4))
                .required("P", p -> p.length(3).width(8).rotatable(true))
                .solver().timeLimit(LIMIT).solve();
        assertThat(turned.isOptimal()).isTrue();
        CutPiece c = turned.assignments().get(0).pieces().get(0);
        assertThat(c.rotated()).isTrue();
        assertThat(c.length()).isEqualTo(8);
        assertThat(c.width()).isEqualTo(3);
        assertValid2D(turned, 0);
    }

    @Test
    void rotationSavesSheets() {
        // Three 6x4 pieces exactly cover a 12x6 sheet only when turned to 4x6, side by side.
        CuttingSolution fixed = Optimize.cuttingStock()
                .stock("S", s -> s.length(12).width(6).quantity(2))
                .required("P", p -> p.length(6).width(4).quantity(3))
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        CuttingSolution free = Optimize.cuttingStock()
                .stock("S", s -> s.length(12).width(6).quantity(2))
                .required("P", p -> p.length(6).width(4).quantity(3).rotatable(true))
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(fixed.isOptimal()).isTrue();
        assertThat(free.isOptimal()).isTrue();
        assertThat(fixed.totalStockUsed()).isEqualTo(2);
        assertThat(free.totalStockUsed()).isEqualTo(1);
        assertValid2D(fixed, 0);
        assertValid2D(free, 0);
        assertThat(free.assignments().get(0).pieces()).allMatch(CutPiece::rotated);
    }

    @Test
    void aSquarePieceNeverReportsATurn() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(2).width(2))
                .required("Sq", p -> p.length(2).width(2).rotatable(true))
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.assignments().get(0).pieces().get(0).rotated()).isFalse();
    }

    @Test
    void kerfSeparatesPiecesOnASheet() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(21).width(10).quantity(2))
                .required("P", p -> p.length(10).width(10).quantity(2))
                .kerf(2)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.totalStockUsed()).as("10 + 2 + 10 > 21").isEqualTo(2);
        assertValid2D(sol, 2);
    }

    @Test
    void identicalPiecesAndSheetsKeepTheSameOptimum() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(20).width(10).quantity(4))
                .required("A", p -> p.length(10).width(5).quantity(4))
                .required("B", p -> p.length(20).width(5).quantity(2))
                .allowDroppingPieces(10_000)
                .goal(Goals.minimizeWaste())
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.totalStockUsed()).isEqualTo(2);
        assertThat(sol.unplacedPieces()).isEmpty();
        assertValid2D(sol, 0);
    }

    // ── Guillotine ──────────────────────────────────────────────────────────

    private static CuttingBuilder pinwheel(boolean guillotine) {
        // Four 2x1 bars around a 1x1 square tile a 3x3 sheet only as a pinwheel, which no
        // edge-to-edge cut can separate.
        return Optimize.cuttingStock()
                .stock("S", s -> s.length(3).width(3).quantity(2))
                .required("H", p -> p.length(2).width(1).quantity(2))
                .required("V", p -> p.length(1).width(2).quantity(2))
                .required("C", p -> p.length(1).width(1))
                .guillotine(guillotine)
                .goal(Goals.minimizeStockUsed());
    }

    @Test
    void guillotineForbidsThePinwheel() {
        CuttingSolution free = pinwheel(false).solver().timeLimit(LIMIT).solve();
        assertThat(free.totalStockUsed()).isEqualTo(1);
        assertValid2D(free, 0);

        CuttingSolution cut = pinwheel(true).solver().timeLimit(LIMIT).solve();
        assertThat(cut.isOptimal()).isTrue();
        assertThat(cut.totalStockUsed()).isEqualTo(2);
        assertValid2D(cut, 0);
        for (StockAssignment a : cut.assignments()) {
            assertThat(isGuillotine(a.pieces(), 0, 0, a.length(), a.width())).as("sheet %s", a.stockId()).isTrue();
        }
        assertThat(isGuillotine(free.assignments().get(0).pieces(), 0, 0, 3, 3))
                .as("the validator rejects the pinwheel").isFalse();
    }

    @Test
    void guillotineHonorsKerfSheetSizesAndDropping() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("Big", s -> s.length(30).width(20).cost(50))
                .stock("Small", s -> s.length(12).width(8).cost(10).quantity(2))
                .required("A", p -> p.length(10).width(8).quantity(3))
                .required("B", p -> p.length(5).width(4).quantity(4))
                .required("Huge", p -> p.length(40).width(5))
                .kerf(1)
                .allowDroppingPieces(1000)
                .guillotine(true)
                .goal(Goals.minimizeTotalCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unplacedPieces()).containsExactly("Huge#1");
        assertValid2D(sol, 1);
        for (StockAssignment a : sol.assignments()) {
            assertThat(isGuillotine(a.pieces(), 0, 0, a.length(), a.width())).isTrue();
        }
    }

    @Test
    void guillotineIsIgnoredIn1D() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("R", s -> s.length(100))
                .required("P", p -> p.length(30).quantity(3))
                .guillotine(true)
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    void invalidCombinationsAreRejected() {
        assertThatThrownBy(() -> Optimize.cuttingStock().kerf(-1))
                .isInstanceOf(ModelException.class).hasMessageContaining("kerf");
        assertThatThrownBy(() -> Optimize.cuttingStock()
                .stock("R", s -> s.length(100))
                .required("P", p -> p.length(30).rotatable(true)).build())
                .isInstanceOf(ModelException.class).hasMessageContaining("2D");
        assertThatThrownBy(() -> Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("P", p -> p.length(3).width(2).rotatable(true))
                .guillotine(true).build())
                .isInstanceOf(ModelException.class).hasMessageContaining("guillotine");
        assertThatThrownBy(() -> Optimize.cuttingStock()
                .stock("S", s -> s.length(Long.MAX_VALUE / 2).width(10))
                .required("P", p -> p.length(3).width(2))
                .kerf(Long.MAX_VALUE / 2 + 10).solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
        assertThatThrownBy(() -> Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("P", p -> p.length(Long.MAX_VALUE / 4).width(10).quantity(8)).solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }
}

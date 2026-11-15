package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.cutting.StockAssignment.CutPiece;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coverage of the 2D goals and dropping paths, warm starts in both dimensions, and id helpers. */
class CuttingFeaturesTest {

    @Test
    void twoDimensionalTotalCostPrefersCheapSheets() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("cheap", s -> s.length(20).width(10).cost(5).quantity(2))
                .stock("dear", s -> s.length(40).width(20).cost(50).quantity(1))
                .required("p", p -> p.length(10).width(10).quantity(3))
                .goal(Goals.minimizeTotalCost())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.objective()).hasValue(10.0);           // two cheap sheets
        for (StockAssignment a : sol.assignments()) {
            if (!a.pieces().isEmpty()) assertThat(a.stockType()).isEqualTo("cheap");
        }
    }

    @Test
    void twoDimensionalDroppingSkipsPiecesThatDoNotFit() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("fits", p -> p.length(5).width(5))
                .required("tooLong", p -> p.length(30).width(5))
                .required("tooWide", p -> p.length(5).width(30))
                .allowDroppingPieces(100)
                .goal(Goals.minimizeStockUsed())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unplacedPieces()).containsExactlyInAnyOrder("tooLong#1", "tooWide#1");
        assertThat(sol.totalStockUsed()).isEqualTo(1);
        assertThat(sol.totalAreaWaste()).isEqualTo(75);
    }

    @Test
    void warmStartInBothDimensionsReproducesTheOptimum() {
        var oneD = Optimize.cuttingStock()
                .stock("R", s -> s.length(100).quantity(4).cost(1))
                .required("A", p -> p.length(60).quantity(3))
                .required("B", p -> p.length(40).quantity(3))
                .goal(Goals.minimizeWaste())
                .build();
        var first1 = oneD.solver().timeLimit(Duration.ofSeconds(10)).solve();
        var again1 = oneD.solver().timeLimit(Duration.ofSeconds(10)).workers(1).warmStart(first1).solve();
        assertThat(again1.isOptimal()).isTrue();
        assertThat(again1.totalWaste()).isEqualTo(first1.totalWaste());

        var twoD = Optimize.cuttingStock()
                .stock("S", s -> s.length(30).width(20).quantity(2))
                .required("A", p -> p.length(15).width(10).quantity(4))
                .required("B", p -> p.length(30).width(10).quantity(1))
                .goal(Goals.minimizeWaste())
                .build();
        var first2 = twoD.solver().timeLimit(Duration.ofSeconds(10)).solve();
        var again2 = twoD.solver().timeLimit(Duration.ofSeconds(10)).workers(1).warmStart(first2).solve();
        assertThat(again2.isOptimal()).isTrue();
        assertThat(again2.objective()).isEqualTo(first2.objective());
    }

    @Test
    void typeHelpersHandleHashInDeclaredIds() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("roll#A", s -> s.length(100).quantity(2))
                .required("part#7", p -> p.length(40).quantity(2))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        StockAssignment used = sol.assignments().stream().filter(a -> !a.pieces().isEmpty()).findFirst().orElseThrow();
        assertThat(used.stockType()).isEqualTo("roll#A");
        assertThat(used.stockId()).startsWith("roll#A#");
        CutPiece c = used.pieces().get(0);
        assertThat(c.pieceType()).isEqualTo("part#7");
        assertThat(c.start()).isEqualTo(c.x());
    }

    @Test
    void stockAndPieceRequireALength() {
        assertThatThrownBy(() -> Optimize.cuttingStock().stock("S", s -> s.cost(1)))
                .isInstanceOf(io.github.janglada.orkit.ModelException.class).hasMessageContaining(".length(");
        assertThatThrownBy(() -> Optimize.cuttingStock().required("P", p -> p.quantity(2)))
                .isInstanceOf(io.github.janglada.orkit.ModelException.class).hasMessageContaining(".length(");
    }

    @Test
    void twoDimensionalPieceLargerThanEverySheetIsInfeasibleWithoutDropping() {
        CuttingSolution sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("big", p -> p.length(20).width(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.status()).isEqualTo(io.github.janglada.orkit.SolveStatus.INFEASIBLE);
        assertThat(sol.objective()).isEmpty();
        assertThat(sol.assignments()).isEmpty();
    }

    @Test
    void warmStartFromADifferentModelIgnoresUnknownIds() {
        var previous = Optimize.cuttingStock()
                .stock("old", s -> s.length(100).quantity(2))
                .required("gone", p -> p.length(40).quantity(2))
                .required("A", p -> p.length(60))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        var sol = Optimize.cuttingStock()
                .stock("R", s -> s.length(100).quantity(2))
                .required("A", p -> p.length(60).quantity(2))
                .solver().timeLimit(Duration.ofSeconds(5)).warmStart(previous).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.unplacedPieces()).isEmpty();
    }
}

package opt.examples;

import opt.cutting.CuttingBuilder;
import opt.cutting.CuttingSolution;
import opt.cutting.Goals;
import opt.cutting.StockAssignment;
import opt.cutting.StockAssignment.CutPiece;

import java.time.Duration;

/**
 * Steel roll cutting-stock — cut demanded piece lengths from steel rolls of three
 * available cross-sections (6 m, 8 m, 10 m), minimizing total trim waste.
 *
 * <p>Demonstrates: 1D cutting stock, per-roll NoOverlap, required pieces, quantity
 * expansion for both stock and pieces, and the {@link Goals#minimizeWaste()} objective.
 *
 * <p>Run: mvn exec:java -Dexec.mainClass=opt.examples.SteelRollCuttingStock
 */
public class SteelRollCuttingStock {

    /**
     * Builds the cutting-stock model, solves it with CP-SAT, and prints the per-roll
     * cut layout and total trim waste.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║  Steel Roll Cutting Stock — 1D              ║");
        System.out.println("╚═════════════════════════════════════════════╝");

        CuttingSolution solution = new CuttingBuilder()
                .stock("S6",  s -> s.length(6000).cost(60).quantity(2))
                .stock("S8",  s -> s.length(8000).cost(85).quantity(2))
                .stock("S10", s -> s.length(10000).cost(110).quantity(2))

                .required("beam-2200", p -> p.length(2200).quantity(6))
                .required("beam-1800", p -> p.length(1800).quantity(4))
                .required("beam-1500", p -> p.length(1500).quantity(3))
                .required("beam-900",  p -> p.length(900).quantity(5))
                .required("beam-600",  p -> p.length(600).quantity(4))

                .minimize(Goals.minimizeWaste())
                .solver()
                .timeLimit(Duration.ofSeconds(15))
                .workers(8)
                .solve();

        System.out.printf("%nStatus        : %s%n", solution.status());
        System.out.printf("StockUsed     : %d roll(s)%n", solution.totalStockUsed());
        System.out.printf("TotalWaste    : %d mm%n%n", solution.totalWaste());

        System.out.println("── Roll assignments ───────────────────────────────────────────");
        System.out.printf("%-8s  %-8s  %-7s  %s%n", "Roll", "Length", "Waste", "Pieces (id@start..end)");
        System.out.println("-".repeat(60));
        for (StockAssignment a : solution.assignments()) {
            String pieces = a.pieces().isEmpty() ? "-" : renderPieces(a.pieces());
            System.out.printf("%-8s  %-8d  %-7d  %s%n",
                    a.stockId(),
                    a.length(),
                    a.waste(),
                    pieces);
        }

        if (!solution.unplacedPieces().isEmpty()) {
            System.out.printf("%nUnplaced: %s%n", solution.unplacedPieces());
        }
    }

    private static String renderPieces(java.util.List<CutPiece> cuts) {
        java.util.List<CutPiece> sorted = new java.util.ArrayList<>(cuts);
        sorted.sort(java.util.Comparator.comparingLong(CutPiece::start));
        java.util.StringJoiner sj = new java.util.StringJoiner(", ");
        for (CutPiece c : sorted) {
            sj.add(c.pieceId() + "@" + c.start() + ".." + (c.start() + c.length()));
        }
        return sj.toString();
    }
}

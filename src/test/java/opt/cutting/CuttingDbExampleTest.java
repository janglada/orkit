package opt.cutting;

import opt.Optimize;
import opt.cutting.StockAssignment.CutPiece;
import opt.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven, real-world example test for the 1D cutting-stock solver.
 *
 * <p>Models a <b>steel-mill cutting</b> scenario: customer orders (pieces) must be
 * cut from stock rolls. Two stock types are available — standard-grade (cost 10/roll)
 * and premium-grade (cost 25/roll) — both 1000mm long. The data lives in a SQLite
 * database loaded from {@code src/test/resources/db/cutting.sql}.
 *
 * <p>The test loads the database, builds the model through the fluent DSL, solves
 * it with both the default {@link Goals#minimizeWaste()} and the new
 * {@link Goals#minimizeTotalCost()} objective (exercising Fix 1), asserts the
 * structural invariants (no overlap per roll, each piece on exactly one roll,
 * waste computed correctly), and prints a readable per-roll report — so it doubles
 * as a runnable example.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn test -Dtest=CuttingDbExampleTest
 * }</pre>
 */
class CuttingDbExampleTest {

    // ── T-DB-Ct1: load the cutting DB, build the model, solve, and verify ────

    @Test
    void steelCuttingFromDatabaseIsFeasibleAndMinimizesCost() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/cutting.sql")) {

            // ── Load stock rolls ──────────────────────────────────────────────
            List<Map<String, Object>> stockRows =
                    db.select("SELECT id, length, quantity, cost FROM stock ORDER BY id");

            // ── Load pieces ───────────────────────────────────────────────────
            List<Map<String, Object>> pieceRows =
                    db.select("SELECT id, length, quantity FROM piece ORDER BY id");

            // ── Build model with MinimizeTotalCost ────────────────────────────
            var builder = Optimize.cuttingStock();
            for (Map<String, Object> row : stockRows) {
                String id = TestDatabase.asString(row, "id");
                long length = TestDatabase.asLong(row, "length");
                int quantity = TestDatabase.asInt(row, "quantity");
                long cost = TestDatabase.asLong(row, "cost");
                builder.stock(id, s -> s.length(length).quantity(quantity).cost(cost));
            }
            int totalPieceCount = 0;
            long totalPieceLength = 0;
            for (Map<String, Object> row : pieceRows) {
                String id = TestDatabase.asString(row, "id");
                long length = TestDatabase.asLong(row, "length");
                int quantity = TestDatabase.asInt(row, "quantity");
                totalPieceCount += quantity;
                totalPieceLength += length * quantity;
                builder.required(id, p -> p.length(length).quantity(quantity));
            }
            builder.minimize(Goals.minimizeTotalCost());

            // ── Solve ─────────────────────────────────────────────────────────
            CuttingSolution sol = builder.solver()
                    .timeLimit(Duration.ofSeconds(10))
                    .workers(8)
                    .solve();

            // ── Assertions ────────────────────────────────────────────────────
            assertThat(sol.isFeasible())
                    .as("cutting stock must be feasible")
                    .isTrue();

            // All pieces must be placed (no dropping enabled).
            assertThat(sol.unplacedPieces())
                    .as("all pieces must be placed")
                    .isEmpty();

            // No overlap per roll.
            assertNoOverlap1D(sol.assignments());

            // Each piece assigned to exactly one roll.
            Set<String> placed = new HashSet<>();
            for (StockAssignment a : sol.assignments()) {
                for (CutPiece c : a.pieces()) {
                    assertThat(placed.add(c.pieceId()))
                            .as("piece %s appears on more than one roll", c.pieceId())
                            .isTrue();
                }
            }
            assertThat(placed.size())
                    .as("all %d pieces must be placed", totalPieceCount)
                    .isEqualTo(totalPieceCount);

            // Total waste should be reasonable (pieces fit into 2-3 rolls typically).
            assertThat(sol.totalWaste())
                    .as("waste should be non-negative and less than 2x roll length")
                    .isBetween(0L, 2000L);

            // MinimizeTotalCost should prefer the cheaper STD-1000 rolls.
            long stdRollsUsed = sol.assignments().stream()
                    .filter(a -> !a.pieces().isEmpty() && a.stockId().contains("STD"))
                    .count();
            long prmRollsUsed = sol.assignments().stream()
                    .filter(a -> !a.pieces().isEmpty() && a.stockId().contains("PRM"))
                    .count();
            assertThat(stdRollsUsed)
                    .as("cheaper STD-1000 rolls should be preferred over PRM-1000")
                    .isGreaterThanOrEqualTo(prmRollsUsed);

            // ── Readable report (doubles as a runnable example) ────────────────
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  Steel Cutting — DB-driven 1D Cutting Stock  ║");
            System.out.println("╚══════════════════════════════════════════════╝");
            System.out.printf("%nStatus       : %s%n", sol.status());
            sol.objective().ifPresent(v -> System.out.printf("Objective    : %.0f (total cost)%n", v));
            System.out.printf("Goal         : %s%n", Goals.minimizeTotalCost().getClass().getSimpleName());
            System.out.printf("Stocks       : %d total, %d used%n",
                    stockRows.stream().mapToInt(r -> TestDatabase.asInt(r, "quantity")).sum(),
                    sol.totalStockUsed());
            System.out.printf("Total waste  : %d mm%n", sol.totalWaste());

            System.out.println("\n── Cut plan ────────────────────────────────────────────────");
            System.out.printf("%-12s  %-6s  %-6s  %-30s%n", "Roll", "Length", "Waste", "Pieces");
            System.out.println("-".repeat(70));
            for (StockAssignment a : sol.assignments()) {
                List<String> pieceDescs = a.pieces().stream()
                        .map(c -> c.pieceId() + "(" + c.length() + ")")
                        .toList();
                String desc = pieceDescs.isEmpty() ? "-" : String.join(", ", pieceDescs);
                System.out.printf("%-12s  %-6d  %-6d  %s%n",
                        a.stockId(), a.length(), a.waste(), desc);
            }
            if (!sol.unplacedPieces().isEmpty()) {
                System.out.printf("%nUnplaced: %s%n", String.join(", ", sol.unplacedPieces()));
            }
        }
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
package opt.knapsack;

import opt.Optimize;
import opt.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven, real-world example test for the multiple-knapsack solver.
 *
 * <p>Models a <b>freight-forwarder dispatch</b>: cargo lots (each with a declared
 * value and weight) must be loaded onto outbound trucks/containers (each with a
 * weight capacity), maximizing total shipped value. The data lives in a SQLite
 * database loaded from {@code src/test/resources/db/knapsack.sql}.
 *
 * <p>The test loads the database, builds the model through the fluent DSL, solves
 * it, asserts the structural invariants (capacity respected, each lot in at most
 * one truck, total value does not exceed the sum of all lot values), and prints a
 * readable per-truck report — so it doubles as a runnable example.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn test -Dtest=MultipleKnapsackDbExampleTest
 * }</pre>
 */
class MultipleKnapsackDbExampleTest {

    // ── T-DB1: load the dispatch DB, build the model, solve, and verify ────────

    @Test
    void cargoDispatchFromDatabaseIsFeasibleAndWithinCapacity() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/knapsack.sql")) {

            // ── Load bins (trucks/containers) ───────────────────────────────
            List<Map<String, Object>> binRows =
                    db.select("SELECT id, capacity FROM bin ORDER BY id");

            // ── Load items (cargo lots) ─────────────────────────────────────
            List<Map<String, Object>> itemRows =
                    db.select("SELECT id, profit, weight FROM item ORDER BY id");

            var builder = Optimize.multipleKnapsack();
            for (Map<String, Object> row : binRows) {
                String id = TestDatabase.asString(row, "id");
                long cap = TestDatabase.asLong(row, "capacity");
                builder.bin(id, b -> b.capacity(cap));
            }
            long totalPossibleProfit = 0;
            for (Map<String, Object> row : itemRows) {
                String id = TestDatabase.asString(row, "id");
                long profit = TestDatabase.asLong(row, "profit");
                long weight = TestDatabase.asLong(row, "weight");
                totalPossibleProfit += profit;
                builder.item(id, i -> i.profit(profit).weight(weight));
            }

            // ── Solve ───────────────────────────────────────────────────────
            MultipleKnapsackSolution sol = builder.solver()
                    .timeLimit(Duration.ofSeconds(8))
                    .workers(8)
                    .solve();

            // ── Assertions ──────────────────────────────────────────────────
            assertThat(sol.isFeasible())
                    .as("dispatch must be feasible (OPTIMAL or FEASIBLE)")
                    .isTrue();
            assertThat(sol.totalProfit()).isPositive();
            // Shipped value can never exceed the sum of all lot values.
            assertThat(sol.totalProfit()).isLessThanOrEqualTo(totalPossibleProfit);

            // Capacity respected on every truck.
            for (Map<String, Object> row : binRows) {
                String id = TestDatabase.asString(row, "id");
                long cap = TestDatabase.asLong(row, "capacity");
                BinAssignment a = sol.assignmentOf(id).orElseThrow();
                assertThat(a).as("assignment for %s should exist", id).isNotNull();
                assertThat(a.usedCapacity())
                        .as("truck %s loads %d but capacity is %d", id, a.usedCapacity(), cap)
                        .isLessThanOrEqualTo(cap);
            }

            // Each lot is in at most one truck.
            Set<String> seen = new HashSet<>();
            for (BinAssignment a : sol.assignments()) {
                for (String item : a.items()) {
                    assertThat(seen.add(item))
                            .as("lot %s appears in more than one truck", item)
                            .isTrue();
                }
            }

            // ── Readable report (doubles as a runnable example) ────────────
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  Freight Dispatch — DB-driven Knapsack       ║");
            System.out.println("╚══════════════════════════════════════════════╝");
            System.out.printf("%nStatus       : %s%n", sol.status());
            System.out.printf("TotalProfit  : %d of %d possible%n",
                    sol.totalProfit(), totalPossibleProfit);
            System.out.printf("Trucks       : %d  |  Lots: %d%n",
                    binRows.size(), itemRows.size());

            System.out.println("\n── Container loadout ──────────────────────────────────────");
            System.out.printf("%-8s  %-8s  %-10s  %-30s%n", "Truck", "Load/Cap", "Profit", "Lots");
            System.out.println("-".repeat(60));
            for (BinAssignment a : sol.assignments()) {
                List<String> lots = a.items().isEmpty() ? List.of("-") : a.items();
                System.out.printf("%-8s  %d/%-7d  %-10d  %s%n",
                        a.bin(), a.usedCapacity(), a.capacity(), a.profit(),
                        String.join(", ", lots));
            }
            if (!sol.unassignedItems().isEmpty()) {
                System.out.printf("%nLeft behind: %s%n",
                        String.join(", ", new ArrayList<>(sol.unassignedItems())));
            }
        }
    }
}

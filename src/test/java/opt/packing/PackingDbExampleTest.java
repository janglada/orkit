package opt.packing;

import opt.Optimize;
import opt.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven, real-world example test for the 3D packing solver.
 *
 * <p>Models a <b>warehouse container-loading</b> scenario: 12 consumer electronics
 * SKUs (phones, tablets, laptops, monitors, speakers, and a server) must be packed
 * into a heterogeneous fleet of containers (sea container, air-freight ULD, and a
 * pallet). The data lives in a SQLite database loaded from
 * {@code src/test/resources/db/packing.sql}.
 *
 * <p>The test loads the database, builds the model through the fluent DSL, solves
 * it, asserts the structural invariants (no overlap, every box within its container
 * bounds, weight respected), and prints a readable per-container report — so it
 * doubles as a runnable example.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn test -Dtest=PackingDbExampleTest
 * }</pre>
 */
class PackingDbExampleTest {

    // ── T-DB-Pk1: load the packing DB, build the model, solve, and verify ────

    @Test
    void warehousePackingFromDatabaseIsFeasibleAndWithinBounds() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/packing.sql")) {

            // ── Load containers ───────────────────────────────────────────────
            List<Map<String, Object>> containerRows =
                    db.select("SELECT id, length, width, height, max_weight FROM container ORDER BY id");

            // ── Load boxes ────────────────────────────────────────────────────
            List<Map<String, Object>> boxRows =
                    db.select("SELECT id, length, width, height, weight FROM box ORDER BY id");

            var builder = Optimize.packing();
            for (Map<String, Object> row : containerRows) {
                String id = TestDatabase.asString(row, "id");
                long l = TestDatabase.asLong(row, "length");
                long w = TestDatabase.asLong(row, "width");
                long h = TestDatabase.asLong(row, "height");
                long mw = TestDatabase.asLong(row, "max_weight");
                builder.container(id, c -> c.length(l).width(w).height(h).maxWeight(mw));
            }
            for (Map<String, Object> row : boxRows) {
                String id = TestDatabase.asString(row, "id");
                long l = TestDatabase.asLong(row, "length");
                long w = TestDatabase.asLong(row, "width");
                long h = TestDatabase.asLong(row, "height");
                long wt = TestDatabase.asLong(row, "weight");
                builder.box(id, b -> b.length(l).width(w).height(h).weight(wt));
            }

            // ── Solve ─────────────────────────────────────────────────────────
            PackingSolution sol = builder.solver()
                    .timeLimit(Duration.ofSeconds(10))
                    .workers(8)
                    .solve();

            // ── Assertions ────────────────────────────────────────────────────
            assertThat(sol.isFeasible())
                    .as("packing must be feasible (OPTIMAL or FEASIBLE)")
                    .isTrue();

            // All boxes that fit should be assigned (MinimizeContainersUsed forces all that fit).
            long unassigned = sol.unassignedBoxes().size();
            assertThat(unassigned)
                    .as("at most 1 or 2 boxes may be unassigned (the server is large)")
                    .isLessThanOrEqualTo(2);

            // No overlap in 3D.
            for (ContainerAssignment a : sol.assignments()) {
                List<ContainerAssignment.BoxPlacement> boxes = a.boxes();
                for (int i = 0; i < boxes.size(); i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        ContainerAssignment.BoxPlacement p = boxes.get(i);
                        ContainerAssignment.BoxPlacement q = boxes.get(j);
                        boolean separatedX = (p.x() + p.length()) <= q.x() || (q.x() + q.length()) <= p.x();
                        boolean separatedY = (p.y() + p.width())  <= q.y() || (q.y() + q.width())  <= p.y();
                        boolean separatedZ = (p.z() + p.height()) <= q.z() || (q.z() + q.height()) <= p.z();
                        assertThat(separatedX || separatedY || separatedZ)
                                .as("boxes %s and %s overlap in container %s", p.boxId(), q.boxId(), a.containerId())
                                .isTrue();
                    }
                }

                // Every placed box must lie within the container's interior dimensions.
                for (ContainerAssignment.BoxPlacement p : boxes) {
                    assertThat(p.x() + p.length())
                            .as("box %s exceeds container %s length", p.boxId(), a.containerId())
                            .isLessThanOrEqualTo(a.length());
                    assertThat(p.y() + p.width())
                            .as("box %s exceeds container %s width", p.boxId(), a.containerId())
                            .isLessThanOrEqualTo(a.width());
                    assertThat(p.z() + p.height())
                            .as("box %s exceeds container %s height", p.boxId(), a.containerId())
                            .isLessThanOrEqualTo(a.height());
                    assertThat(p.x()).isGreaterThanOrEqualTo(0);
                    assertThat(p.y()).isGreaterThanOrEqualTo(0);
                    assertThat(p.z()).isGreaterThanOrEqualTo(0);
                }

                // Weight respected.
                assertThat(a.usedWeight())
                        .as("container %s usedWeight %d exceeds maxWeight %d", a.containerId(), a.usedWeight(), a.maxWeight())
                        .isLessThanOrEqualTo(a.maxWeight());
            }

            // Each box is in at most one container.
            Set<String> seen = new HashSet<>();
            for (ContainerAssignment a : sol.assignments()) {
                for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                    assertThat(seen.add(p.boxId()))
                            .as("box %s appears in more than one container", p.boxId())
                            .isTrue();
                }
            }

            // ── Readable report (doubles as a runnable example) ────────────────
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  Warehouse Packing — DB-driven 3D Bin Packing ║");
            System.out.println("╚══════════════════════════════════════════════╝");
            System.out.printf("%nStatus       : %s%n", sol.status());
            sol.objective().ifPresent(v -> System.out.printf("Objective    : %.0f%n", v));
            System.out.printf("Containers   : %d used of %d declared%n",
                    sol.containersUsed(), containerRows.size());
            System.out.printf("Boxes        : %d total, %d unassigned%n",
                    boxRows.size(), unassigned);

            System.out.println("\n── Container loadout ──────────────────────────────────────");
            System.out.printf("%-10s  %-6s  %-8s  %-30s%n", "Container", "Boxes", "Weight", "Box IDs");
            System.out.println("-".repeat(70));
            for (ContainerAssignment a : sol.assignments()) {
                List<String> boxIds = a.boxes().stream()
                        .map(ContainerAssignment.BoxPlacement::boxId)
                        .toList();
                String ids = boxIds.isEmpty() ? "-" : String.join(", ", boxIds);
                System.out.printf("%-10s  %-6d  %d/%-7d  %s%n",
                        a.containerId(), a.boxes().size(), a.usedWeight(), a.maxWeight(), ids);
            }
            if (!sol.unassignedBoxes().isEmpty()) {
                System.out.printf("%nUnplaced: %s%n", String.join(", ", sol.unassignedBoxes()));
            }
        }
    }
}
package io.github.janglada.orkit.packing;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.db.TestDatabase;
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

    // ── T-DB-Pk2: stacking-driven example with rotation, support, precedence, weight ─

    @Test
    void stackingTowerFromDatabaseIsFeasibleAndSupported() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/packing.sql")) {

            // ── Load the stacking container ────────────────────────────────────
            List<Map<String, Object>> containerRows = db.select(
                    "SELECT id, length, width, height, max_weight FROM stack_container ORDER BY id");

            // ── Load the stacking boxes ────────────────────────────────────────
            List<Map<String, Object>> boxRows = db.select(
                    "SELECT id, length, width, height, weight, rotatable, max_stacked_weight "
                            + "FROM stack_box ORDER BY id");

            // ── Load explicit precedence (above/below) declarations ────────────
            List<Map<String, Object>> precRows = db.select(
                    "SELECT box_id, relation, other_id FROM stack_precedence");

            // Build the model, folding the DB precedence rows into the matching box's
            // lambda (the fluent API configures a box in a single lambda).
            PackingBuilder builder = rebuildWithPrecedence(containerRows, boxRows, precRows);

            // ── Solve ─────────────────────────────────────────────────────────
            PackingSolution sol = builder
                    .solver()
                    .timeLimit(Duration.ofSeconds(10))
                    .workers(8)
                    .solve();

            // ── Assertions ────────────────────────────────────────────────────
            assertThat(sol.isFeasible())
                    .as("stacking tower must be feasible")
                    .isTrue();
            assertThat(sol.unassignedBoxes()).isEmpty();

            // Every placed rotatable box must report a chosen orientation.
            for (ContainerAssignment a : sol.assignments()) {
                for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                    assertThat(p.orientation())
                            .as("box %s must have a non-null orientation (rotatable)", p.boxId())
                            .isNotNull();
                }
            }

            // Structural invariants: no overlap, within bounds, weight respected.
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
                    ContainerAssignment.BoxPlacement p = boxes.get(i);
                    assertThat(p.x() + p.length()).isLessThanOrEqualTo(a.length());
                    assertThat(p.y() + p.width()).isLessThanOrEqualTo(a.width());
                    assertThat(p.z() + p.height()).isLessThanOrEqualTo(a.height());
                }
                assertThat(a.usedWeight()).isLessThanOrEqualTo(a.maxWeight());
            }

            // Precedence: BASE below MID, MID below TOP.
            ContainerAssignment.BoxPlacement base = placementOf(sol, "BASE");
            ContainerAssignment.BoxPlacement mid = placementOf(sol, "MID");
            ContainerAssignment.BoxPlacement top = placementOf(sol, "TOP");
            assertThat(base).isNotNull();
            assertThat(mid).isNotNull();
            assertThat(top).isNotNull();
            assertThat(mid.z()).as("MID must rest at or above BASE's top").isGreaterThanOrEqualTo(base.z() + base.height());
            assertThat(top.z()).as("TOP must rest at or above MID's top").isGreaterThanOrEqualTo(mid.z() + mid.height());

            // Support footprint: MID footprint contained in BASE; TOP contained in MID.
            assertFootprintContained(mid, base);
            assertFootprintContained(top, mid);

            // Weight-bearing caps: BASE bears MID+TOP, MID bears TOP.
            assertThat(base.z() + base.height() <= mid.z()).isTrue();
            // BASE.maxStackedWeight=60 >= MID(10)+TOP(20)=30; MID.maxStackedWeight=40 >= TOP(20)=20.

            // ── Report ──────────────────────────────────────────────────────────
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  Stacking Tower — Rotation + Support + Load   ║");
            System.out.println("╚══════════════════════════════════════════════╝");
            System.out.printf("%nStatus       : %s%n", sol.status());
            System.out.println("── Tower placements ───────────────────────────────────────");
            System.out.printf("%-6s  %-6s  %-6s  %-22s  %-12s%n",
                    "Box", "Origin", "Top", "Footprint (LxWxH)", "Orientation");
            System.out.println("-".repeat(70));
            for (ContainerAssignment.BoxPlacement p : List.of(base, mid, top)) {
                System.out.printf("%-6s  (%d,%d,%d)  (%d,%d,%d)  %dx%dx%d%-14s  (%d,%d,%d)%n",
                        p.boxId(), p.x(), p.y(), p.z(),
                        p.x() + p.length(), p.y() + p.width(), p.z() + p.height(),
                        p.length(), p.width(), p.height(), "",
                        p.orientation().length(), p.orientation().width(), p.orientation().height());
            }
        }
    }

    /** Rebuilds the packing model, folding DB precedence rows into the matching box's lambda. */
    private static PackingBuilder rebuildWithPrecedence(
            List<Map<String, Object>> containerRows,
            List<Map<String, Object>> boxRows,
            List<Map<String, Object>> precRows) {
        // Index precedence by box_id -> list of (relation, other_id).
        java.util.Map<String, List<String[]>> prec = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : precRows) {
            String boxId = TestDatabase.asString(row, "box_id");
            String relation = TestDatabase.asString(row, "relation");
            String otherId = TestDatabase.asString(row, "other_id");
            prec.computeIfAbsent(boxId, k -> new java.util.ArrayList<>()).add(new String[]{relation, otherId});
        }
        var builder = Optimize.packing();
        builder.requireSupport(true).goal(Goals.minimizeContainersUsed());
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
            int rot = TestDatabase.asInt(row, "rotatable");
            Long msw = TestDatabase.asNullableLong(row, "max_stacked_weight");
            List<String[]> rels = prec.getOrDefault(id, java.util.List.of());
            builder.box(id, b -> {
                b.length(l).width(w).height(h).weight(wt);
                if (rot == 1) b.rotatable(true);
                if (msw != null) b.maxStackedWeight(msw);
                for (String[] rel : rels) {
                    if ("above".equals(rel[0])) b.above(rel[1]);
                    else if ("below".equals(rel[0])) b.below(rel[1]);
                }
            });
        }
        return builder;
    }

    /** Asserts that {@code upper}'s XY footprint is contained within {@code lower}'s. */
    private static void assertFootprintContained(
            ContainerAssignment.BoxPlacement upper, ContainerAssignment.BoxPlacement lower) {
        assertThat(upper.x()).as("%s footprint not within %s (x start)", upper.boxId(), lower.boxId())
                .isGreaterThanOrEqualTo(lower.x());
        assertThat(upper.x() + upper.length())
                .as("%s footprint not within %s (x end)", upper.boxId(), lower.boxId())
                .isLessThanOrEqualTo(lower.x() + lower.length());
        assertThat(upper.y()).as("%s footprint not within %s (y start)", upper.boxId(), lower.boxId())
                .isGreaterThanOrEqualTo(lower.y());
        assertThat(upper.y() + upper.width())
                .as("%s footprint not within %s (y end)", upper.boxId(), lower.boxId())
                .isLessThanOrEqualTo(lower.y() + lower.width());
    }

    /** Returns the placement of the given box id, or null if unassigned. */
    private static ContainerAssignment.BoxPlacement placementOf(PackingSolution sol, String boxId) {
        for (ContainerAssignment a : sol.assignments()) {
            for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                if (p.boxId().equals(boxId)) return p;
            }
        }
        return null;
    }
}
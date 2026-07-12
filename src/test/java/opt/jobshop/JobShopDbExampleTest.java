package opt.jobshop;

import opt.Optimize;
import opt.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven, real-world example test for the job-shop solver.
 *
 * <p>Models a small <b>custom furniture &amp; cabinetry workshop</b> whose
 * manufacturing data lives in a SQLite database (loaded from
 * {@code src/test/resources/db/jobshop.sql}). The schema captures realistic
 * manufacturing concepts:
 * <ul>
 *   <li><b>machines</b> — saw, two identical CNC routers (a flexible group),
 *       edge bander, sander, spray booth, assembly bench, packing station;</li>
 *   <li><b>products</b> with multi-step <b>routings</b> — Cabinet, Table, Shelf,
 *       Door — each a different sequence of operations with concrete durations
 *       in minutes;</li>
 *   <li><b>work orders</b> — concrete customer orders referencing a product.</li>
 * </ul>
 *
 * <p>The test loads the database, builds the flexible job-shop model through the
 * fluent DSL (fixed ops via {@code .on(...)}, flexible CNC ops via
 * {@code .onAnyOf(...)}), solves it, asserts the structural invariants a
 * scheduler must satisfy, and prints a readable per-machine Gantt report — so
 * it doubles as a runnable example.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn test -Dtest=JobShopDbExampleTest
 * }</pre>
 */
class JobShopDbExampleTest {

    // ── T-DB1: load the workshop DB, build the model, solve, and verify ───────

    @Test
    void workshopScheduleFromDatabaseIsFeasibleAndConflictFree() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/jobshop.sql")) {

            // ── Load machines ────────────────────────────────────────────────
            List<Map<String, Object>> machineRows =
                    db.select("SELECT id, name, machine_group FROM machine ORDER BY id");

            // Declared machine list + group → member-machineIds map (flexible pools)
            List<String> machineIds = new ArrayList<>();
            Map<String, List<String>> groupMachines = new LinkedHashMap<>();
            for (Map<String, Object> row : machineRows) {
                String id = TestDatabase.asString(row, "id");
                machineIds.add(id);
                Object group = row.get("machine_group");
                if (group != null) {
                    groupMachines.computeIfAbsent(group.toString(), g -> new ArrayList<>()).add(id);
                }
            }

            // ── Load work orders (each becomes one job) ──────────────────────
            List<Map<String, Object>> orders = db.select(
                    "SELECT order_no, product_id, qty, release_min, due_min "
                    + "FROM work_order ORDER BY order_no");

            // orderNo → expected operation count (from the product's routing)
            Map<String, Integer> expectedSteps = new LinkedHashMap<>();
            // orderNo → 0-based indices of operations that are CNC-flexible
            Map<String, List<Integer>> cncFlexibleOps = new LinkedHashMap<>();
            // orderNo → hard earliest start of the first operation (release date)
            Map<String, Long> releaseOfFirst = new LinkedHashMap<>();
            // orderNo → soft due date of the last operation
            Map<String, Long> dueOfLast = new LinkedHashMap<>();

            var builder = Optimize.jobShop().machines(machineIds.toArray(String[]::new));

            for (Map<String, Object> order : orders) {
                String orderNo   = TestDatabase.asString(order, "order_no");
                String productId = TestDatabase.asString(order, "product_id");
                // SQLite may return small INTEGERs as Integer or Long; coerce via Number.
                Number relNum = (Number) order.get("release_min");
                Number dueNum = (Number) order.get("due_min");
                Long releaseMin = (relNum == null) ? null : relNum.longValue();
                Long dueMin      = (dueNum == null) ? null : dueNum.longValue();
                if (releaseMin != null) releaseOfFirst.put(orderNo, releaseMin);
                if (dueMin     != null) dueOfLast.put(orderNo, dueMin);

                // Routing steps for this product, in step order
                List<Map<String, Object>> steps = db.select(
                        "SELECT step_no, machine_group, machine_id, duration_min " +
                        "FROM routing_step WHERE product_id = ? ORDER BY step_no", productId);

                expectedSteps.put(orderNo, steps.size());
                List<Integer> flexibleIdx = new ArrayList<>();
                for (int i = 0; i < steps.size(); i++) {
                    Object machineGroup = steps.get(i).get("machine_group");
                    if (machineGroup != null && machineGroup.toString().equals("CNC")) {
                        flexibleIdx.add(i);
                    }
                }
                cncFlexibleOps.put(orderNo, flexibleIdx);

                int lastIdx = steps.size() - 1;
                builder.job(orderNo, j -> {
                    OperationConfig op = null;
                    for (int i = 0; i < steps.size(); i++) {
                        Map<String, Object> step = steps.get(i);
                        int duration = TestDatabase.asInt(step, "duration_min");
                        Object machineId    = step.get("machine_id");
                        Object machineGroup = step.get("machine_group");

                        op = (i == 0) ? j.operation() : op.then();
                        if (machineId != null) {
                            // Fixed machine assignment
                            op.on(machineId.toString()).takes(duration);
                        } else {
                            // Flexible: any machine in the group
                            String[] candidates = groupMachines.get(machineGroup.toString())
                                    .toArray(String[]::new);
                            op.onAnyOf(candidates).takes(duration);
                        }
                        // Hard release date on the first operation of the order.
                        if (i == 0 && releaseMin != null) {
                            op.releaseAt(releaseMin);
                        }
                        // Soft due date on the last operation of the order.
                        if (i == lastIdx && dueMin != null) {
                            op.dueBy(dueMin);
                        }
                    }
                });
            }

            // Minimize makespan plus a weighted tardiness penalty so the soft due
            // dates above take effect (release dates are hard constraints regardless).
            builder.minimize(Goals.minimizeMakespanAndTardiness(1, 5));

            // ── Solve ────────────────────────────────────────────────────────
            JobShopSchedule schedule = builder.solver()
                    .timeLimit(Duration.ofSeconds(8))
                    .workers(8)
                    .solve();

            // ── Assertions ───────────────────────────────────────────────────
            // Status is OPTIMAL or FEASIBLE
            assertThat(schedule.isFeasible())
                    .as("schedule must be feasible (OPTIMAL or FEASIBLE)")
                    .isTrue();
            assertThat(schedule.makespan()).isPositive();

            // Every work order has all its routing steps scheduled
            for (Map.Entry<String, Integer> e : expectedSteps.entrySet()) {
                assertThat(schedule.operationsOf(e.getKey()))
                        .as("order %s should have %d scheduled operations", e.getKey(), e.getValue())
                        .hasSize(e.getValue());
            }

            // No machine is ever double-booked (non-overlap per machine timeline)
            for (String machine : machineIds) {
                assertNoOverlap(schedule.timelineOf(machine), machine);
            }

            // Precedence holds within each job (operation i ends before i+1 starts)
            for (String orderNo : expectedSteps.keySet()) {
                assertPrecedence(schedule.operationsOf(orderNo), orderNo);
            }

            // Hard release dates: the first op of a released order starts at or after its release.
            for (Map.Entry<String, Long> e : releaseOfFirst.entrySet()) {
                ScheduledOperation first = schedule.operationsOf(e.getKey()).get(0);
                assertThat(first.start())
                        .as("order %s first op must start at or after release %d",
                                e.getKey(), e.getValue())
                        .isGreaterThanOrEqualTo(e.getValue());
            }

            // Flexible CNC operations land on an actual CNC-group machine
            List<String> cncMembers = groupMachines.getOrDefault("CNC", List.of());
            for (Map.Entry<String, List<Integer>> e : cncFlexibleOps.entrySet()) {
                String orderNo = e.getKey();
                List<ScheduledOperation> ops = schedule.operationsOf(orderNo);
                for (int idx : e.getValue()) {
                    assertThat(cncMembers)
                            .as("CNC-flexible op %d of %s must run on a CNC-group machine", idx, orderNo)
                            .contains(ops.get(idx).machine());
                }
            }

            // ── Readable report (doubles as a runnable example) ─────────────
            System.out.println("╔════════════════════════════════════════════════╗");
            System.out.println("║  Furniture Workshop — DB-driven Job Shop       ║");
            System.out.println("╚════════════════════════════════════════════════╝");
            System.out.printf("%nStatus  : %s%n", schedule.status());
            System.out.printf("Makespan: %d minutes (%.1f hours)%n",
                    schedule.makespan(), schedule.makespan() / 60.0);
            System.out.printf("Jobs    : %d  |  Machines: %d%n",
                    expectedSteps.size(), machineIds.size());

            Gantt gantt = schedule.asGantt();
            System.out.println("\n── Gantt chart (time in minutes) ──────────────────────────────");
            System.out.printf("%-6s  %-10s  %-6s  %-6s  %-6s%n",
                    "Machine", "Order", "Start", "End", "Dur");
            System.out.println("-".repeat(46));
            for (String machine : gantt.machines()) {
                List<ScheduledOperation> ops = gantt.rowFor(machine);
                if (ops.isEmpty()) continue;
                for (ScheduledOperation op : ops) {
                    System.out.printf("%-6s  %-10s  %-6d  %-6d  %-6d%n",
                            machine, op.job(), op.start(), op.end(), op.end() - op.start());
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertNoOverlap(List<ScheduledOperation> ops, String machine) {
        for (int i = 0; i < ops.size() - 1; i++) {
            ScheduledOperation a = ops.get(i);
            ScheduledOperation b = ops.get(i + 1);
            assertThat(a.end())
                    .as("Overlap on %s between %s-op%d and %s-op%d",
                            machine, a.job(), a.index(), b.job(), b.index())
                    .isLessThanOrEqualTo(b.start());
        }
    }

    private void assertPrecedence(List<ScheduledOperation> ops, String job) {
        for (int i = 0; i < ops.size() - 1; i++) {
            assertThat(ops.get(i).end())
                    .as("Precedence violated in %s between op%d and op%d", job, i, i + 1)
                    .isLessThanOrEqualTo(ops.get(i + 1).start());
        }
    }
}

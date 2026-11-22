package io.github.janglada.orkit.examples;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.jobshop.Gantt;
import io.github.janglada.orkit.jobshop.JobShopSchedule;
import io.github.janglada.orkit.jobshop.ScheduledOperation;

import java.time.Duration;
import java.util.List;

/**
 * PCB electronics manufacturing — flexible job shop with 8 board types and 5 machines.
 *
 * Demonstrates: flexible operations (onAnyOf), multiple jobs sharing machines,
 * makespan minimization, Gantt chart output.
 *
 * Machines:
 *   SCREEN    — solder-paste screen printing
 *   PP1, PP2  — pick-and-place (two identical machines, flexible assignment)
 *   REFLOW    — reflow oven (solders components)
 *   AOI       — automated optical inspection
 *
 * Each board type (job) follows: SCREEN → PP1|PP2 (flexible) → REFLOW → AOI
 * Duration varies by board complexity (in minutes).
 *
 * Run: mvn exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.PcbManufacturingJobShop
 */
public class PcbManufacturingJobShop {

    /**
     * Builds the PCB flexible job-shop model, solves it with CP-SAT, and prints the
     * makespan, per-machine Gantt chart, and the pick-and-place machine assignments.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║   PCB Manufacturing — Flexible Job Shop     ║");
        System.out.println("╚═════════════════════════════════════════════╝");

        JobShopSchedule schedule = Optimize.jobShop()
                .machines("SCREEN", "PP1", "PP2", "REFLOW", "AOI")

                // Standard boards (mid complexity)
                .job("Standard-A", j -> j
                        .operation().on("SCREEN").takes(8)
                        .then().onAnyOf("PP1", "PP2").takes(12)
                        .then().on("REFLOW").takes(20)
                        .then().on("AOI").takes(5))

                .job("Standard-B", j -> j
                        .operation().on("SCREEN").takes(8)
                        .then().onAnyOf("PP1", "PP2").takes(10)
                        .then().on("REFLOW").takes(20)
                        .then().on("AOI").takes(5))

                .job("Standard-C", j -> j
                        .operation().on("SCREEN").takes(8)
                        .then().onAnyOf("PP1", "PP2").takes(14)
                        .then().on("REFLOW").takes(20)
                        .then().on("AOI").takes(5))

                // Complex boards (many components → longer pick-and-place)
                .job("Complex-X", j -> j
                        .operation().on("SCREEN").takes(10)
                        .then().onAnyOf("PP1", "PP2").takes(28)
                        .then().on("REFLOW").takes(25)
                        .then().on("AOI").takes(8))

                .job("Complex-Y", j -> j
                        .operation().on("SCREEN").takes(10)
                        .then().onAnyOf("PP1", "PP2").takes(32)
                        .then().on("REFLOW").takes(25)
                        .then().on("AOI").takes(8))

                // Prototype boards (double reflow pass for reliability)
                .job("Proto-1", j -> j
                        .operation().on("SCREEN").takes(12)
                        .then().onAnyOf("PP1", "PP2").takes(18)
                        .then().on("REFLOW").takes(40)   // double pass
                        .then().on("AOI").takes(10))

                .job("Proto-2", j -> j
                        .operation().on("SCREEN").takes(12)
                        .then().onAnyOf("PP1", "PP2").takes(22)
                        .then().on("REFLOW").takes(40)   // double pass
                        .then().on("AOI").takes(10))

                // Rush order — simple board but must fit in the schedule
                .job("Rush-Order", j -> j
                        .operation().on("SCREEN").takes(6)
                        .then().onAnyOf("PP1", "PP2").takes(8)
                        .then().on("REFLOW").takes(18)
                        .then().on("AOI").takes(4))

                .solver()
                .timeLimit(Duration.ofSeconds(30))
                .workers(8)
                .solve();

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus  : %s%n", schedule.status());
        System.out.printf("Makespan: %d minutes (%.1f hours)%n",
                schedule.makespan(), schedule.makespan() / 60.0);
        schedule.bestBound().ifPresent(b -> System.out.printf("Bound   : %.0f minutes (gap %.1f%%)%n",
                b, 100 * schedule.gap().orElse(0)));
        System.out.println();

        // Gantt chart per machine
        Gantt gantt = schedule.asGantt();
        System.out.println("── Gantt Chart (time in minutes) ─────────────────────────────────────");
        System.out.printf("%-10s  %-14s  %-10s  %-10s  %-10s%n",
                "Machine", "Job", "Start", "End", "Duration");
        System.out.println("-".repeat(62));

        for (String machine : gantt.machines()) {
            List<ScheduledOperation> ops = gantt.rowFor(machine);
            if (ops.isEmpty()) continue;
            for (ScheduledOperation op : ops) {
                System.out.printf("%-10s  %-14s  %-10d  %-10d  %-10d%n",
                        machine, op.job(), op.start(), op.end(), op.end() - op.start());
            }
            System.out.println();
        }

        // Validate: PP1/PP2 flexible assignments
        System.out.println("── Pick-and-Place machine assignments ────────────────────────────────");
        String[] jobs = {"Standard-A","Standard-B","Standard-C","Complex-X","Complex-Y","Proto-1","Proto-2","Rush-Order"};
        for (String job : jobs) {
            ScheduledOperation ppOp = schedule.operationsOf(job).get(1); // index 1 = PP step
            System.out.printf("  %-14s → %s (start=%d, end=%d)%n",
                    job, ppOp.machine(), ppOp.start(), ppOp.end());
        }
    }
}

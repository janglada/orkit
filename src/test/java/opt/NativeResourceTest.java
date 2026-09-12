package opt;

import opt.routing.DistanceSource;
import opt.routing.Goals;
import opt.routing.RoutingPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards against leaks of native (off-heap) OR-Tools memory.
 *
 * OR-Tools' Java bindings are SWIG wrappers whose native memory the GC reclaims only via
 * {@code finalize()} — deprecated and non-deterministic, and invisible to GC heap-pressure
 * heuristics. {@code RoutingCompiler} therefore deletes its {@code RoutingIndexManager} /
 * {@code RoutingModel} / {@code Assignment} explicitly; CP-SAT's {@code CpSolver} exposes
 * no delete API (its internal {@code SolveWrapper} is private and freed by finalization
 * after {@code solve()} drops the reference), so the CP-SAT case forces GC to verify
 * eventual reclamation, while the routing case verifies GC-independent reclamation.
 *
 * Measured on OR-Tools 9.11.4210 before explicit deletes were added: this exact no-GC
 * routing loop grew RSS linearly, ~1.85 MB per solve (554 MB over 300 solves). After
 * adding explicit deletes: ~18 MB total drift over 300 solves (Java heap noise).
 */
class NativeResourceTest {

    /** Slack for heap expansion/JIT noise; the historical leak was ~1.8 MB per routing solve. */
    private static final long MAX_GROWTH_KB = 96 * 1024;

    /**
     * Runs the routing loop in a child JVM with a large heap so the GC never fires —
     * native reclamation must then come from {@code RoutingCompiler}'s explicit deletes,
     * not from finalizers. (In-process this condition cannot be guaranteed: the surefire
     * JVM may GC spontaneously, masking a missing delete.)
     */
    @Test
    void routingReleasesNativeMemoryWithoutGc() throws Exception {
        assumeTrue(rssKb() > 0, "requires Linux /proc");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaBin, "-Xmx4g", "-cp", System.getProperty("java.class.path"),
                ChildMain.class.getName(), "150")
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes());
        assertThat(child.waitFor(120, TimeUnit.SECONDS))
                .as("child JVM finished in time.\nOutput:\n%s", output).isTrue();

        long growthKb = Long.parseLong(
                output.lines().filter(l -> l.startsWith("GROWTH_KB=")).findFirst()
                        .orElseThrow(() -> new AssertionError("child did not report growth.\nOutput:\n" + output))
                        .substring("GROWTH_KB=".length()));
        assertThat(growthKb)
                .as("RSS growth over 150 routing solves with no GC (explicit delete must free natively)")
                .isLessThan(MAX_GROWTH_KB);
    }

    /** CP-SAT frees natively only via finalization — force GC and verify bounded RSS. */
    @Test
    void cpSatNativeMemoryIsReclaimed() throws Exception {
        assumeTrue(rssKb() > 0, "requires Linux /proc");

        for (int i = 0; i < 50; i++) solveTinyKnapsack();  // warmup
        drainFinalizers();
        long baseline = rssKb();

        for (int i = 0; i < 400; i++) solveTinyKnapsack();
        drainFinalizers();                                 // finalizers free SolveWrapper
        long growth = rssKb() - baseline;

        assertThat(growth)
                .as("RSS growth over 400 CP-SAT solves after finalization")
                .isLessThan(MAX_GROWTH_KB);
    }

    /** Child-JVM entry point: solves in a loop, never triggers GC, prints GROWTH_KB=&lt;n&gt;. */
    public static final class ChildMain {
        public static void main(String[] args) throws Exception {
            int iters = Integer.parseInt(args[0]);
            for (int i = 0; i < 15; i++) solveTinyRouting();  // warmup: JIT, native arenas
            long baseline = rssKb();
            for (int i = 0; i < iters; i++) solveTinyRouting();
            System.out.println("GROWTH_KB=" + (rssKb() - baseline));
        }
    }

    private static void solveTinyRouting() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(200))
                .stop("A", s -> s.at(41.400, 2.170).demand(10))
                .stop("B", s -> s.at(41.380, 2.155).demand(15))
                .stop("C", s -> s.at(41.395, 2.180).demand(20))
                .distances(DistanceSource.haversine())
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofMillis(100)).solve();
        assertThat(plan.isFeasible()).isTrue();
    }

    private static void solveTinyKnapsack() {
        var sol = Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10))
                .bin("B2", b -> b.capacity(10))
                .item("I1", i -> i.profit(10).weight(6))
                .item("I2", i -> i.profit(8).weight(5))
                .item("I3", i -> i.profit(6).weight(5))
                .item("I4", i -> i.profit(7).weight(4))
                .item("I5", i -> i.profit(3).weight(3))
                .solver().timeLimit(Duration.ofSeconds(2)).solve();
        assertThat(sol.totalProfit()).isEqualTo(31);
    }

    @SuppressWarnings("removal")
    private static void drainFinalizers() throws InterruptedException {
        for (int k = 0; k < 3; k++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(50);
        }
    }

    /** @return VmRSS in kB, or -1 if /proc is unavailable. */
    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (IOException ignored) {
            // fall through
        }
        return -1;
    }
}

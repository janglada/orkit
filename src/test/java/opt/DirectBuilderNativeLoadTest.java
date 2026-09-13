package opt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for native-library loading. Builders may be constructed directly
 * ({@code new PackingBuilder()}) without ever touching {@link Optimize}, whose static
 * initializer used to be the only loader. CP-SAT variable creation crosses into JNI, so
 * the library must be loaded as soon as a solver exists. This runs in a child JVM because
 * in the shared surefire JVM some other test has almost certainly loaded it already.
 */
class DirectBuilderNativeLoadTest {

    @Test
    void everyBuilderSolvesWithoutTouchingOptimize() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                ChildMain.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes());
        assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("child finished.\n%s", output).isTrue();
        assertThat(child.exitValue()).as("child exit code.\n%s", output).isZero();
        for (String domain : new String[]{"jobshop", "scheduling", "routing", "knapsack", "packing", "booking", "cutting"}) {
            assertThat(output).as("child output").contains("OK " + domain);
        }
    }

    /** Uses only direct builder constructors; must never reference {@link Optimize}. */
    public static final class ChildMain {
        public static void main(String[] args) {
            var js = new opt.jobshop.JobShopBuilder().machines("M1")
                    .job("J", j -> j.operation().on("M1").takes(2))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("jobshop", js.isFeasible());

            var sched = new opt.scheduling.SchedulingBuilder().horizon(LocalDate.of(2026, 7, 6), 1)
                    .employee("e", e -> {})
                    .shift("s", s -> s.on(LocalDate.of(2026, 7, 6)).from(LocalTime.of(8, 0)).to(LocalTime.of(12, 0)))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("scheduling", sched.isFeasible());

            var route = new opt.routing.RoutingBuilder().depot("D", 41.35, 2.13)
                    .vehicle("V", v -> v.capacity(10))
                    .stop("A", s -> s.at(41.36, 2.14).demand(1))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("routing", route.isFeasible());

            var knap = new opt.knapsack.MultipleKnapsackBuilder().bin("B", b -> b.capacity(5))
                    .item("I", i -> i.profit(1).weight(1))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("knapsack", knap.isFeasible());

            var pack = new opt.packing.PackingBuilder()
                    .container("C", c -> c.length(5).width(5).height(5).maxWeight(10))
                    .box("B", b -> b.length(1).width(1).height(1).weight(1))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("packing", pack.isFeasible());

            var book = new opt.booking.BookingBuilder().horizon(LocalDate.of(2026, 7, 6), 1)
                    .resource("R", r -> {})
                    .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R"))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("booking", book.isFeasible());

            var cut = new opt.cutting.CuttingBuilder().stock("S", s -> s.length(10))
                    .required("P", p -> p.length(4))
                    .solver().timeLimit(Duration.ofSeconds(5)).solve();
            check("cutting", cut.isFeasible());
        }

        private static void check(String domain, boolean ok) {
            if (!ok) {
                System.out.println("FAILED " + domain);
                System.exit(2);
            }
            System.out.println("OK " + domain);
        }
    }
}

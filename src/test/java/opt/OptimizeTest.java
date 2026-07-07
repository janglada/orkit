package opt;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@link Optimize} facade: each factory returns a fresh, independent
 * builder, and every factory produces a builder that can solve a trivial
 * instance (moves the manual {@code SmokeTest} coverage into JUnit).
 */
class OptimizeTest {

    // ── Each factory returns a fresh, independent builder ─────────────────────

    @Test
    void factoriesReturnFreshBuildersEachCall() {
        assertThat(Optimize.jobShop()).isNotSameAs(Optimize.jobShop());
        assertThat(Optimize.scheduling()).isNotSameAs(Optimize.scheduling());
        assertThat(Optimize.vehicleRouting()).isNotSameAs(Optimize.vehicleRouting());
        assertThat(Optimize.multipleKnapsack()).isNotSameAs(Optimize.multipleKnapsack());
        assertThat(Optimize.packing()).isNotSameAs(Optimize.packing());
        assertThat(Optimize.booking()).isNotSameAs(Optimize.booking());
        assertThat(Optimize.cuttingStock()).isNotSameAs(Optimize.cuttingStock());
    }

    @Test
    void configuringOneBuilderDoesNotLeakIntoAnother() {
        var a = Optimize.jobShop().machines("M1");
        var b = Optimize.jobShop(); // independent
        a.job("J1", j -> j.operation().on("M1").takes(3));
        // b has no jobs at all => solving it must fail, proving no state leaked from a.
        assertThatThrownBy(() -> b.solver().timeLimit(Duration.ofSeconds(2)).solve())
                .isInstanceOf(ModelException.class);
    }

    // ── Every factory can solve a trivial instance ────────────────────────────

    @Test
    void jobShopFactorySolvesTrivialInstance() {
        var sol = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void schedulingFactorySolvesTrivialInstance() {
        var sol = Optimize.scheduling()
                .horizon(LocalDate.of(2026, 1, 5), 1)
                .employee("E", e -> e.skills("RN").costPerHour(30))
                .shift("s", sh -> sh.on(LocalDate.of(2026, 1, 5))
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void routingFactorySolvesTrivialInstance() {
        var sol = Optimize.vehicleRouting()
                .depot("D", 41.35, 2.13)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(41.40, 2.20).demand(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void knapsackFactorySolvesTrivialInstance() {
        var sol = Optimize.multipleKnapsack()
                .bin("B", b -> b.capacity(10))
                .item("I", i -> i.profit(5).weight(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void packingFactorySolvesTrivialInstance() {
        var sol = Optimize.packing()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(10))
                .box("X", x -> x.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void bookingFactorySolvesTrivialInstance() {
        var sol = Optimize.booking()
                .horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    @Test
    void cuttingFactorySolvesTrivialInstance() {
        var sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(100))
                .required("P", p -> p.length(40))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }
}

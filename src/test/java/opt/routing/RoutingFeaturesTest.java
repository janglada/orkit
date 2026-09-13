package opt.routing;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deeper coverage of the routing features: search-parameter mappings, FIFO ordering,
 * vehicle availability, skills with dropping, validation guards, and structural invariants
 * (times monotonic and inside windows, load within capacity) on a mixed instance.
 */
class RoutingFeaturesTest {

    private static RoutingBuilder bcn() {
        return Optimize.vehicleRouting().depot("Depot", 41.35, 2.13);
    }

    /** Structural invariants every feasible plan must satisfy. */
    static void assertPlanInvariants(RoutingPlan plan, Map<String, Integer> capacityOf,
                                     Map<String, LocalTime[]> windowOf, Map<String, Integer> serviceOf) {
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.vehiclesUsed()).isEqualTo(plan.routes().size());
        assertThat(plan.totalDistanceMeters()).isEqualTo(plan.routes().stream().mapToLong(Route::distanceMeters).sum());
        for (Route r : plan.routes()) {
            assertThat(r.visits()).hasSize(r.stops().size());
            for (int i = 0; i < r.stops().size(); i++) assertThat(r.visits().get(i).stop()).isEqualTo(r.stops().get(i));
            Integer cap = capacityOf.get(r.vehicle());
            LocalTime prev = r.startTime();
            for (Visit v : r.visits()) {
                assertThat(v.loadAfter()).isGreaterThanOrEqualTo(0);
                if (cap != null) assertThat(v.loadAfter()).as("load on %s", r.vehicle()).isLessThanOrEqualTo(cap);
                if (r.hasTimes()) {
                    assertThat(v.arrival()).as("arrival order on %s", r.vehicle()).isAfterOrEqualTo(prev);
                    assertThat(v.departure()).isEqualTo(v.arrival().plusMinutes(serviceOf.getOrDefault(v.stop(), 0)));
                    LocalTime[] w = windowOf.get(v.stop());
                    if (w != null) assertThat(v.arrival()).isAfterOrEqualTo(w[0]).isBeforeOrEqualTo(w[1]);
                    prev = v.departure();
                } else {
                    assertThat(v.arrival()).isNull();
                    assertThat(v.departure()).isNull();
                }
            }
            if (r.hasTimes()) assertThat(r.endTime()).isAfterOrEqualTo(prev);
        }
    }

    @Test
    void mixedInstanceSatisfiesAllInvariants() {
        Map<String, Integer> cap = Map.of("V1", 10, "V2", 10, "Cold", 6);
        Map<String, LocalTime[]> win = Map.of(
                "A", new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(11, 0)},
                "B", new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(12, 0)},
                "Fish", new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(10, 0)});
        Map<String, Integer> svc = Map.of("A", 10, "B", 5, "P", 5, "D", 5);
        RoutingPlan plan = bcn()
                .vehicle("V1", v -> v.capacity(10).availableFrom(LocalTime.of(7, 30)).availableUntil(LocalTime.of(14, 0)))
                .vehicle("V2", v -> v.capacity(10).fixedCost(1000))
                .vehicle("Cold", v -> v.capacity(6).skills("cold").maxRouteDuration(Duration.ofHours(5)))
                .stop("A", s -> s.at(41.39, 2.16).demand(4).openFrom(win.get("A")[0]).closeAt(win.get("A")[1]).serviceTime(Duration.ofMinutes(10)))
                .stop("B", s -> s.at(41.38, 2.19).demand(3).openFrom(win.get("B")[0]).closeAt(win.get("B")[1]).serviceTime(Duration.ofMinutes(5)))
                .stop("C", s -> s.at(41.40, 2.15).demand(5))
                .stop("Fish", s -> s.at(41.37, 2.17).demand(2).requires("cold").openFrom(win.get("Fish")[0]).closeAt(win.get("Fish")[1]))
                .stop("P", s -> s.at(41.36, 2.10).demand(4).serviceTime(Duration.ofMinutes(5)))
                .stop("D", s -> s.at(41.33, 2.05).serviceTime(Duration.ofMinutes(5)))
                .pickupAndDelivery("P", "D")
                .goal(Goals.totalCost())
                .solver().timeLimit(Duration.ofSeconds(8)).solve();
        assertPlanInvariants(plan, cap, win, svc);
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.routeOf("Cold").map(Route::stops).orElse(List.of())).contains("Fish");
        Route v1 = plan.routeOf("V1").orElse(null);
        if (v1 != null) {
            assertThat(v1.startTime()).isAfterOrEqualTo(LocalTime.of(7, 30));
            assertThat(v1.endTime()).isBeforeOrEqualTo(LocalTime.of(14, 0));
        }
        assertThat(plan.objective()).hasValue((double) (plan.totalDistanceMeters() + plan.totalFixedCost()));
    }

    @Test
    void everyFirstSolutionStrategyAndMetaheuristicIsAccepted() {
        for (Strategy s : Strategy.values()) {
            RoutingPlan plan = bcn()
                    .vehicle("V", v -> v.capacity(20))
                    .stop("A", s1 -> s1.at(41.39, 2.16).demand(4))
                    .stop("B", s1 -> s1.at(41.38, 2.19).demand(3))
                    .allowDroppingStops(1_000_000)
                    .solver().timeLimit(Duration.ofSeconds(1)).firstSolution(s).solve();
            assertThat(plan.status()).as("strategy %s", s).isNotNull();
        }
        for (Metaheuristic m : Metaheuristic.values()) {
            RoutingPlan plan = bcn()
                    .vehicle("V", v -> v.capacity(20))
                    .stop("A", s1 -> s1.at(41.39, 2.16).demand(4))
                    .stop("B", s1 -> s1.at(41.38, 2.19).demand(3))
                    .solver().timeLimit(Duration.ofSeconds(1)).improveWith(m).verbose(false).solve();
            assertThat(plan.isFeasible()).as("metaheuristic %s", m).isTrue();
        }
    }

    @Test
    void fifoPolicyDeliversInPickupOrder() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(10))
                .stop("P1", s -> s.at(41.39, 2.16).demand(1)).stop("D1", s -> s.at(41.42, 2.20))
                .stop("P2", s -> s.at(41.395, 2.165).demand(1)).stop("D2", s -> s.at(41.425, 2.205))
                .pickupAndDelivery("P1", "D1").pickupAndDelivery("P2", "D2")
                .pickupAndDeliveryPolicy(PickupDeliveryPolicy.FIFO)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        List<String> seq = plan.routeOf("V").orElseThrow().stops();
        int p1 = seq.indexOf("P1"), d1 = seq.indexOf("D1"), p2 = seq.indexOf("P2"), d2 = seq.indexOf("D2");
        // FIFO: whichever was picked up first is delivered first.
        assertThat(p1 < p2).isEqualTo(d1 < d2);
    }

    @Test
    void availableUntilMakesLateStopsInfeasibleOrDropped() {
        long[][] meters = {{0, 1000, 1000}, {1000, 0, 1000}, {1000, 1000, 0}};
        long[][] minutes = {{0, 60, 60}, {60, 0, 60}, {60, 60, 0}};
        RoutingPlan strict = Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.availableFrom(LocalTime.of(8, 0)).availableUntil(LocalTime.of(10, 30)))
                .stop("A", s -> s.demand(1)).stop("B", s -> s.demand(1))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(strict.isFeasible()).as("3 h of travel cannot fit in a 2.5 h window").isFalse();

        RoutingPlan dropping = Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.availableFrom(LocalTime.of(8, 0)).availableUntil(LocalTime.of(10, 30)))
                .stop("A", s -> s.demand(1)).stop("B", s -> s.demand(1))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .allowDroppingStops(1_000_000)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(dropping.isFeasible()).isTrue();
        assertThat(dropping.unassignedStops()).hasSize(1);
        Route r = dropping.routes().get(0);
        assertThat(r.startTime()).isAfterOrEqualTo(LocalTime.of(8, 0));
        assertThat(r.endTime()).isBeforeOrEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void zeroTravelTimeInMatrixIsRespected() {
        long[][] meters = {{0, 5000}, {5000, 0}};
        long[][] minutes = {{0, 0}, {0, 0}};          // teleporting: no floor of 1 minute applied
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.availableFrom(LocalTime.of(9, 0)))
                .stop("A", s -> s.demand(1))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        Route r = plan.routeOf("V").orElseThrow();
        assertThat(r.visits().get(0).arrival()).isEqualTo(LocalTime.of(9, 0));
        assertThat(r.endTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void skillRequirementCombinedWithDropping() {
        RoutingPlan plan = bcn()
                .vehicle("Plain", v -> v.capacity(1))
                .vehicle("Reefer", v -> v.capacity(1).skills("cold"))
                .stop("Fish1", s -> s.at(41.39, 2.16).demand(1).requires("cold"))
                .stop("Fish2", s -> s.at(41.40, 2.17).demand(1).requires("cold"))
                .stop("Bread", s -> s.at(41.38, 2.19).demand(1))
                .allowDroppingStops(1_000_000)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        // Reefer can carry only one fish; the other fish must be dropped, never handed to Plain.
        assertThat(plan.unassignedStops()).hasSize(1).allMatch(id -> id.startsWith("Fish"));
        assertThat(plan.routeOf("Plain").map(Route::stops).orElse(List.of())).containsExactly("Bread");
    }

    @Test
    void endsAtOnlyVehicleStartsAndEndsThere() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("West", 41.347, 2.126).depot("East", 41.404, 2.200)
                .vehicle("V", v -> v.capacity(20).endsAt("East"))
                .stop("A", s -> s.at(41.40, 2.19).demand(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.routeOf("V").orElseThrow().depotId()).isEqualTo("East");
    }

    @Test
    void mixedCapacitatedAndUnboundedVehicles() {
        RoutingPlan plan = bcn()
                .vehicle("Small", v -> v.capacity(1))
                .vehicle("Big", v -> {})                    // unbounded
                .stop("A", s -> s.at(41.39, 2.16).demand(50))
                .stop("B", s -> s.at(41.38, 2.19).demand(50))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routeOf("Big").orElseThrow().stops()).containsExactlyInAnyOrder("A", "B");
        assertThat(plan.routeOf("Small")).isEmpty();
    }

    @Test
    void matricesMustMatchNodeCountAtSolveTime() {
        long[][] m2 = {{0, 1}, {1, 0}};
        long[][] m3 = {{0, 1, 1}, {1, 0, 1}, {1, 1, 0}};
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> {}).stop("A", s -> {}).stop("B", s -> {})
                .distances(DistanceSource.fromMatrices(m2, m2)).solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("3×3");
        assertThatThrownBy(() -> DistanceSource.fromMatrices(m3, null))
                .isInstanceOf(ModelException.class).hasMessageContaining("Time matrix");
        assertThatThrownBy(() -> DistanceSource.fromMatrix(new long[0][0]))
                .isInstanceOf(ModelException.class).hasMessageContaining("non-empty");
    }

    @Test
    void configurationNullGuards() {
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.availableFrom(null))).isInstanceOf(ModelException.class).hasMessageContaining("availableFrom");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.availableUntil(null))).isInstanceOf(ModelException.class).hasMessageContaining("availableUntil");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.maxRouteDuration(null))).isInstanceOf(ModelException.class).hasMessageContaining("maxRouteDuration");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.maxRouteDuration(Duration.ZERO))).isInstanceOf(ModelException.class).hasMessageContaining("maxRouteDuration");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.skills(" "))).isInstanceOf(ModelException.class).hasMessageContaining("skill");
        assertThatThrownBy(() -> bcn().stop("A", s -> s.serviceTime(Duration.ofMinutes(-1)))).isInstanceOf(ModelException.class).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> bcn().stop("A", s -> s.requires(""))).isInstanceOf(ModelException.class).hasMessageContaining("skill");
        assertThatThrownBy(() -> bcn().pickupAndDeliveryPolicy(null)).isInstanceOf(ModelException.class).hasMessageContaining("pickupAndDeliveryPolicy");
        assertThatThrownBy(() -> bcn().pickupAndDelivery(" ", "D")).isInstanceOf(ModelException.class).hasMessageContaining("pickup stop id");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {}).stop("A", s -> s.at(41.39, 2.16))
                .solver().timeLimit(null)).isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
    }

    @Test
    void verboseDoesNotChangeTheResult() {
        var problem = bcn().vehicle("V", v -> v.capacity(20))
                .stop("A", s -> s.at(41.39, 2.16).demand(4))
                .stop("B", s -> s.at(41.38, 2.19).demand(3))
                .build();
        var quiet = problem.solver().timeLimit(Duration.ofSeconds(3)).verbose(false).solve();
        var loud = problem.solver().timeLimit(Duration.ofSeconds(3)).verbose(true).solve();
        assertThat(loud.objective()).isEqualTo(quiet.objective());
        assertThat(loud.status()).isIn(SolveStatus.FEASIBLE, SolveStatus.OPTIMAL);
    }

    @Test
    void routeLoadExcludesDeliveriesButVisitsTrackOnBoardLoad() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(10))
                .stop("X", s -> s.at(41.38, 2.19).demand(2))
                .stop("P", s -> s.at(41.39, 2.16).demand(3))
                .stop("D", s -> s.at(41.42, 2.20).demand(3))    // explicit equal demand is allowed
                .pickupAndDelivery("P", "D")
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        Route r = plan.routeOf("V").orElseThrow();
        assertThat(r.load()).isEqualTo(5);                   // X + P, not D
        int atD = r.visits().get(r.stops().indexOf("D")).loadAfter();
        int atP = r.visits().get(r.stops().indexOf("P")).loadAfter();
        assertThat(atP - atD).isEqualTo(3);
    }

    @Test
    void unknownPickupIdIsRejectedAndRuntimeIsReported() {
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {})
                .stop("D", s -> s.at(41.39, 2.16))
                .pickupAndDelivery("NOPE", "D").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown stop id 'NOPE'");
        RoutingPlan plan = bcn().vehicle("V", v -> {}).stop("A", s -> s.at(41.39, 2.16))
                .solver().timeLimit(Duration.ofSeconds(2)).solve();
        assertThat(plan.runtime().isNegative()).isFalse();
    }
}

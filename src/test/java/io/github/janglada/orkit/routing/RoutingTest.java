package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoutingTest {

    // ── T-R1: Single vehicle visits all stops ────────────────────────────────

    @Test
    void singleVehicleVisitsAllStops() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(200))
                .stop("A", s -> s.at(41.400, 2.170).demand(10))
                .stop("B", s -> s.at(41.380, 2.155).demand(15))
                .stop("C", s -> s.at(41.395, 2.180).demand(20))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        Set<String> allVisited = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toSet());
        assertThat(allVisited).containsExactlyInAnyOrder("A", "B", "C");

        List<String> flatStops = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toList());
        assertThat(flatStops).doesNotHaveDuplicates();
    }

    // ── T-R2: Capacity constraint splits stops across two vehicles ───────────

    @Test
    void capacityConstraintRequiresTwoVehicles() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(45))
                .vehicle("v2", v -> v.capacity(45))
                .stop("A", s -> s.at(41.400, 2.165).demand(20))
                .stop("B", s -> s.at(41.385, 2.155).demand(20))
                .stop("C", s -> s.at(41.395, 2.175).demand(20))
                .stop("D", s -> s.at(41.375, 2.162).demand(20))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        long activeVehicles = plan.routes().stream().filter(r -> !r.stops().isEmpty()).count();
        assertThat(activeVehicles).isEqualTo(2);

        for (Route r : plan.routes()) {
            assertThat(r.load()).as("Route for %s exceeds capacity", r.vehicle()).isLessThanOrEqualTo(45);
        }
    }

    // ── T-R3: Time windows force visit ordering ───────────────────────────────

    @Test
    void timeWindowForcesCorrectVisitOrder() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("earlyStop", s -> s.at(41.395, 2.162).demand(1)
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(9, 0)))
                .stop("lateStop",  s -> s.at(41.395, 2.162).demand(1)
                        .openFrom(LocalTime.of(14, 0)).closeAt(LocalTime.of(15, 0)))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        List<String> stops = plan.routes().get(0).stops();
        assertThat(stops.indexOf("earlyStop")).isLessThan(stops.indexOf("lateStop"));
    }

    // ── T-R4: Low penalty causes drops ───────────────────────────────────────

    @Test
    void droppedStopsReportedInUnassigned() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(10))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .allowDroppingStops(1)
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops().size()).isGreaterThanOrEqualTo(2);
    }

    // ── T-R5: Precomputed matrix — exact symmetric tour ──────────────────────

    @Test
    void precomputedMatrixDistancesUsed() {
        long[][] matrix = {
            {0,   100, 200},
            {100, 0,   150},
            {200, 150, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .stop("B", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.objective()).hasValue(450.0);
    }

    // ── T-R6: Missing depot validation ───────────────────────────────────────

    @Test
    void missingDepotThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(41.400, 2.165).demand(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("depot");
    }

    // ── T-R7: No vehicles validation ─────────────────────────────────────────

    @Test
    void noVehiclesThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .stop("A", s -> s.at(41.400, 2.165).demand(10))
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-R8: Bulk vehicle helper ─────────────────────────────────────────────

    @Test
    void vehiclesBulkHelperCreatesCorrectCount() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicles(3, v -> v.capacity(50))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.routes().size()).isLessThanOrEqualTo(3);
    }

    // ── T-R9: Structural — route.load() equals sum of stop demands ───────────

    @Test
    void routeLoadEqualsSumOfStopDemands() {
        // demand: A=10, B=25, C=15, D=30
        long[][] matrix = {
            {0,  500, 500, 500, 500},
            {500, 0,  100, 100, 100},
            {500, 100, 0,  100, 100},
            {500, 100, 100, 0,  100},
            {500, 100, 100, 100, 0 }
        };
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))
                .vehicle("v2", v -> v.capacity(100))
                .stop("A", s -> s.at(0.0, 0.0).demand(10))
                .stop("B", s -> s.at(0.0, 0.0).demand(25))
                .stop("C", s -> s.at(0.0, 0.0).demand(15))
                .stop("D", s -> s.at(0.0, 0.0).demand(30))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();

        Map<String, Integer> demandOf = Map.of("A", 10, "B", 25, "C", 15, "D", 30);
        for (Route r : plan.routes()) {
            int expected = r.stops().stream().mapToInt(demandOf::get).sum();
            assertThat(r.load())
                    .as("Route for %s: load field does not match sum of demands", r.vehicle())
                    .isEqualTo(expected);
        }
    }

    // ── T-R10: Structural — objective equals sum of route distances ──────────

    @Test
    void objectiveEqualsSumOfRouteDistances() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .vehicle("v2", v -> v.capacity(100))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();

        long routeDistanceSum = plan.routes().stream().mapToLong(Route::distanceMeters).sum();
        assertThat(plan.objective()).hasValue((double) routeDistanceSum);
    }

    // ── T-R11: Structural — all stops appear in routes or unassigned ─────────

    @Test
    void allStopsAccountedForInRoutesOrUnassigned() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(20))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .allowDroppingStops(1)
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();

        Set<String> inRoutes = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toSet());
        Set<String> unassigned = new HashSet<>(plan.unassignedStops());

        // Every stop must appear in exactly one place
        assertThat(inRoutes).doesNotContainAnyElementsOf(unassigned);
        Set<String> all = new HashSet<>(inRoutes);
        all.addAll(unassigned);
        assertThat(all).containsExactlyInAnyOrder("A", "B", "C");
    }

    // ── T-R12: Haversine produces plausible real-world distances ─────────────

    @Test
    void haversineDistanceIsPlausible() {
        // Depot and single stop roughly 750m apart (same latitude, 0.009° longitude difference).
        // Round-trip ≈ 1500m; assert objective is in the [1200, 1800] m range.
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("near", s -> s.at(41.390, 2.169).demand(1))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.objective().getAsDouble())
                .as("Round-trip to a ~750m stop should be roughly 1500m")
                .isCloseTo(1500.0, within(300.0));
    }

    // ── T-R13: Wrong-sized precomputed matrix throws ModelException ──────────

    @Test
    void wrongSizedMatrixThrowsModelException() {
        // 3×3 matrix but 1 depot + 3 stops = needs 4×4
        long[][] tooSmall = {
            {0,   100, 200},
            {100, 0,   150},
            {200, 150, 0  }
        };

        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 0.0, 0.0)
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(0.0, 0.0).demand(1))
                        .stop("B", s -> s.at(0.0, 0.0).demand(1))
                        .stop("C", s -> s.at(0.0, 0.0).demand(1))
                        .distances(DistanceSource.fromMatrix(tooSmall))
                        .solver().solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("4×4");
    }

    // ── T-R14: High penalty forces all stops served ───────────────────────────

    @Test
    void highPenaltyForcesAllStopsServed() {
        // Even with tight capacity, a very high penalty ensures no drops
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(10))
                .vehicle("v2", v -> v.capacity(10))
                .vehicle("v3", v -> v.capacity(10))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .allowDroppingStops(1_000_000_000L) // penalty far exceeds any route cost
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
    }

    // ── T-R15: maxDistanceMeters limits per-vehicle route length ─────────────

    @Test
    void maxDistancePerVehicleIsEnforced() {
        // Near stop: 1000m round trip. Far stop: 16000m round trip.
        // v1 capped at 3000m → can only take Near.
        // v2 uncapped → must take Far.
        long[][] matrix = {
            {0,    500,  8000},   // depot → Near=500, Far=8000
            {500,  0,    8000},   // Near → depot=500, Far=8000
            {8000, 8000, 0   }    // Far  → depot=8000, Near=8000
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100).maxDistanceMeters(3000))
                .vehicle("v2", v -> v.capacity(100))
                .stop("Near", s -> s.at(0.0, 0.0).demand(1))
                .stop("Far",  s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        // v1's total route distance must respect its cap
        plan.routes().stream()
                .filter(r -> r.vehicle().equals("v1"))
                .forEach(r -> assertThat(r.distanceMeters())
                        .as("v1 exceeded its maxDistanceMeters(3000)")
                        .isLessThanOrEqualTo(3000));

        // "Far" requires an 8000m one-way trip — its round trip alone (16000m) exceeds v1's cap
        boolean farInV1 = plan.routes().stream()
                .filter(r -> r.vehicle().equals("v1"))
                .anyMatch(r -> r.stops().contains("Far"));
        assertThat(farInV1).as("Far is unreachable within v1's 3000m limit").isFalse();
    }

    // ── T-R16: Asymmetric matrix — directional costs respected ───────────────

    @Test
    void asymmetricMatrixOptimalTourUsesDirectionalCosts() {
        // Going depot→A is cheap (10m); returning A→depot is expensive (500m).
        // Going A→B is cheap (10m); B→depot is cheap (10m).
        // Optimal: depot→A→B→depot = 10+10+10 = 30m.
        // Reverse: depot→B→A→depot = 100+500+500 = 1100m (asymmetric penalty).
        long[][] matrix = {
            {0,   10,  100},   // from depot
            {500, 0,   10 },   // from A
            {10,  500, 0  }    // from B
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .stop("B", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        // Optimal tour cost is 30m; the reverse would be 1100m
        assertThat(plan.objective()).hasValue(30.0);
        assertThat(plan.routes().get(0).stops()).containsExactly("A", "B");
    }

    // ── T-R17: Heterogeneous vehicles — heavy stop goes to high-capacity vehicle

    @Test
    void heavyStopAssignedToHighCapacityVehicle() {
        // light stop (demand 5) fits in small vehicle (cap 10).
        // heavy stop (demand 80) does NOT fit in small vehicle; must go to large (cap 100).
        long[][] matrix = {
            {0,   500, 500},
            {500, 0,   100},
            {500, 100, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("small", v -> v.capacity(10))
                .vehicle("large", v -> v.capacity(100))
                .stop("light", s -> s.at(0.0, 0.0).demand(5))
                .stop("heavy", s -> s.at(0.0, 0.0).demand(80))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        // heavy can only fit in large; verify capacity constraint holds on every route
        for (Route r : plan.routes()) {
            int cap = r.vehicle().equals("small") ? 10 : 100;
            assertThat(r.load()).as("%s exceeds capacity %d", r.vehicle(), cap).isLessThanOrEqualTo(cap);
        }

        // heavy must be in the large vehicle's route
        boolean heavyInLarge = plan.routes().stream()
                .filter(r -> r.vehicle().equals("large"))
                .anyMatch(r -> r.stops().contains("heavy"));
        assertThat(heavyInLarge).isTrue();
    }

    // ── T-R18: No stops throws ModelException ────────────────────────────────

    @Test
    void noStopsThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .vehicle("v1", v -> v.capacity(100))
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-R19: Reusable problem — two solver runs give consistent results ─────

    @Test
    void reusableProblemGivesConsistentResults() {
        var problem = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .build();

        RoutingPlan r1 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        RoutingPlan r2 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(r1.isFeasible()).isTrue();
        assertThat(r2.isFeasible()).isTrue();
        assertThat(r1.unassignedStops()).isEmpty();
        assertThat(r2.unassignedStops()).isEmpty();
        // Both runs must cover the same stops
        Set<String> visited1 = r1.routes().stream().flatMap(r -> r.stops().stream()).collect(Collectors.toSet());
        Set<String> visited2 = r2.routes().stream().flatMap(r -> r.stops().stream()).collect(Collectors.toSet());
        assertThat(visited1).isEqualTo(visited2);
    }

    // ── T-R20: Input validation — negative demand ────────────────────────────

    @Test
    void negativeDemandThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(41.400, 2.165).demand(-5))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("-5");
    }

    // ── T-R21: Input validation — zero capacity ───────────────────────────────

    @Test
    void zeroCapacityThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .vehicle("v1", v -> v.capacity(0))
                        .stop("A", s -> s.at(41.400, 2.165).demand(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("0");
    }

    // ── T-R22: Input validation — inverted time window ────────────────────────

    @Test
    void invertedTimeWindowThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(41.400, 2.165).demand(1)
                                .openFrom(LocalTime.of(14, 0))
                                .closeAt(LocalTime.of(9, 0)))  // close before open
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-R23: Zero-demand stops are still visited ────────────────────────────

    @Test
    void zeroDemandsStopsAreVisited() {
        // demand=0 is valid (e.g., a mandatory waypoint with no pickup/delivery)
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("waypoint", s -> s.at(41.395, 2.165).demand(0))
                .stop("delivery", s -> s.at(41.385, 2.170).demand(10))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        Set<String> visited = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toSet());
        assertThat(visited).contains("waypoint", "delivery");
    }

    // ── T-R24: configurable travel speed affects time dimension ──────────────

    @Test
    void travelSpeedKmhDoesNotBreakFeasibility() {
        // A stop with a time window reachable at 80 km/h urban speed.
        // The key check: setting a non-default speed does not crash and still finds a solution.
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(41.395, 2.165).demand(5)
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(18, 0)))
                .stop("B", s -> s.at(41.385, 2.170).demand(5))
                .distances(DistanceSource.haversine())
                .travelSpeedKmh(25.0)   // urban speed
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
    }

    // ── T-R25: Single stop — known round-trip distance from matrix ───────────

    @Test
    void singleStopRoundTripDistanceMatchesMatrix() {
        // depot→stop = 750m, stop→depot = 750m → round trip = 1500m
        long[][] matrix = {
            {0,   750},
            {750, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))
                .stop("only", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.objective()).hasValue(1500.0);
        assertThat(plan.routes()).hasSize(1);
        assertThat(plan.routes().get(0).stops()).containsExactly("only");
        assertThat(plan.routes().get(0).distanceMeters()).isEqualTo(1500);
    }

    // ── T-R26: Over capacity with no dropping is infeasible ─────────────────────

    @Test
    void overCapacityWithoutDroppingIsInfeasible() {
        // Single vehicle cap 10, one stop demand 100 — unservable without dropping.
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(10))
                .stop("A", s -> s.at(41.400, 2.170).demand(100))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-R27: Per-vehicle max distance too small is infeasible ─────────────────

    @Test
    void maxDistanceTooSmallIsInfeasible() {
        // Stop ~9.5 km from depot → round trip ~19 km, but vehicle capped at 50 m.
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(10).maxDistanceMeters(50))
                .stop("A", s -> s.at(41.500, 2.300).demand(1))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-R28: Blank depot/vehicle/stop ids are rejected ─────────────────────

    @Test
    void blankIdsThrowModelException() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("", 0, 0))
                .isInstanceOf(ModelException.class).hasMessageContaining("depot id");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("", v -> v.capacity(10)))
                .isInstanceOf(ModelException.class).hasMessageContaining("vehicle id");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("", s -> s.at(0, 0).demand(4)))
                .isInstanceOf(ModelException.class).hasMessageContaining("stop id");
    }

    // ── T-R29: Duplicate vehicle/stop ids are rejected ───────────────────────

    @Test
    void duplicateIdsThrowModelException() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4)))
                .isInstanceOf(ModelException.class).hasMessageContaining("Duplicate vehicle id");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4))
                .stop("S", s -> s.at(0, 0).demand(4)))
                .isInstanceOf(ModelException.class).hasMessageContaining("Duplicate stop id");
    }

    // ── T-R30: vehicles(0, ...) leaves no vehicles and throws at solve ───────

    @Test
    void vehiclesZeroCountThrowsAtSolve() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicles(0, v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("vehicle");
    }

    // ── T-R31: serviceTime(null) rejected; 1-minute boundary accepted ────────

    @Test
    void serviceTimeNullAndBoundary() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4).serviceTime(null)))
                .isInstanceOf(ModelException.class).hasMessageContaining("serviceTime");

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(new long[][]{{0, 100}, {100, 0}}))
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4).serviceTime(Duration.ofMinutes(1)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
    }

    // ── T-R32: Zero-width and inverted time windows are rejected ─────────────

    @Test
    void zeroWidthAndInvertedTimeWindowsThrowModelException() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4)
                        .openFrom(LocalTime.of(0, 0)).closeAt(LocalTime.of(0, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("strictly before");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4)
                        .openFrom(LocalTime.of(12, 0)).closeAt(LocalTime.of(12, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("strictly before");
    }

    // ── T-R33: maxDistanceMeters / travelSpeedKmh boundaries ─────────────────

    @Test
    void negativeMaxDistanceAndSpeedThrowModelException() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.maxDistanceMeters(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
        assertThatThrownBy(() -> Optimize.vehicleRouting().travelSpeedKmh(0))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> Optimize.vehicleRouting().travelSpeedKmh(-1))
                .isInstanceOf(ModelException.class);
    }

    // ── T-R34: distances(null) and malformed matrices are rejected ───────────

    @Test
    void nullAndMalformedMatricesThrowModelException() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0).distances(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("distances");
        assertThatThrownBy(() -> DistanceSource.fromMatrix(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("null");
        assertThatThrownBy(() -> DistanceSource.fromMatrix(new long[][]{{0, 1}, {1}}))
                .isInstanceOf(ModelException.class).hasMessageContaining("square");
        assertThatThrownBy(() -> DistanceSource.fromMatrix(new long[0][]))
                .isInstanceOf(ModelException.class);
    }

    // ── T-R35: allowDroppingStops(0) disables dropping; positive drops ───────

    @Test
    void zeroPenaltyDisablesDroppingPositivePenaltyDrops() {
        // demand 100 > cap 10, no dropping => INFEASIBLE
        RoutingPlan tight = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(new long[][]{{0, 100}, {100, 0}}))
                .vehicle("V", v -> v.capacity(10))
                .allowDroppingStops(0)
                .stop("S", s -> s.at(0, 0).demand(100))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(tight.status()).isEqualTo(SolveStatus.INFEASIBLE);

        // same instance with dropping allowed => stop is dropped, feasible
        RoutingPlan droppy = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(new long[][]{{0, 100}, {100, 0}}))
                .vehicle("V", v -> v.capacity(10))
                .allowDroppingStops(1)
                .stop("S", s -> s.at(0, 0).demand(100))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(droppy.isFeasible()).isTrue();
        assertThat(droppy.unassignedStops()).containsExactly("S");
    }

    // ── T-R36: Idle vehicles are omitted; all-dropped yields no routes ───────

    @Test
    void idleVehiclesOmittedAndAllDroppedYieldsNoRoutes() {
        // Two vehicles, one stop => only one vehicle used; the idle one is omitted.
        RoutingPlan oneRoute = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(new long[][]{{0, 100}, {100, 0}}))
                .vehicle("V1", v -> v.capacity(10))
                .vehicle("V2", v -> v.capacity(10))
                .stop("S", s -> s.at(0, 0).demand(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(oneRoute.routes()).hasSize(1);

        // Two unservable stops with dropping => both dropped, no routes.
        RoutingPlan allDropped = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(
                        new long[][]{{0, 100, 100}, {100, 0, 100}, {100, 100, 0}}))
                .vehicle("V", v -> v.capacity(10))
                .allowDroppingStops(1)
                .stop("S1", s -> s.at(0, 0).demand(100))
                .stop("S2", s -> s.at(0, 0).demand(100))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(allDropped.isFeasible()).isTrue();
        assertThat(allDropped.unassignedStops()).hasSize(2);
        assertThat(allDropped.routes()).isEmpty();
    }

    // ── T-R37: Capacity exactly met is feasible ──────────────────────────────

    @Test
    void capacityExactlyMetIsFeasible() {
        // cap 10, two stops of demand 5 each => sum == capacity, must be feasible.
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .distances(DistanceSource.fromMatrix(
                        new long[][]{{0, 100, 100}, {100, 0, 100}, {100, 100, 0}}))
                .vehicle("V", v -> v.capacity(10))
                .stop("S1", s -> s.at(0, 0).demand(5))
                .stop("S2", s -> s.at(0, 0).demand(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
    }

    // ── T-R38: build().solver() returns RoutingSolver with firstSolution/improveWith ──

    @Test
    void buildReturnsRoutingProblemWithTypedSolver() {
        // Verify that build() returns a RoutingProblem whose solver() is a
        // RoutingSolver, so firstSolution and improveWith are available without
        // a cast. This is the post-build path (the direct solver() path already works).
        RoutingProblem problem = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .distances(DistanceSource.haversine())
                .goal(Goals.totalDistance())
                .build();

        // The key assertion: build() returns RoutingProblem, not just OptimizationProblem.
        // If it compiles, the return type is correct.
        RoutingPlan plan = problem.solver()
                .timeLimit(Duration.ofSeconds(5))
                .firstSolution(Strategy.PATH_CHEAPEST_ARC)
                .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
                .solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
    }

    // ── T-R39: Multiple depots partition stops by vehicle depot ──────────────

    @Test
    void multipleDepotsPartitionStopsByVehicleDepot() {
        // Nodes: 0=d1, 1=d2, 2=A (near d1), 3=B (near d2). Cross arcs are huge.
        long[][] matrix = {
            {0,    9999, 100,  9999},   // d1 → d2/A/B
            {9999, 0,    9999, 100 },   // d2 → d1/A/B
            {100,  9999, 0,    9999},   // A  → d1/d2/B
            {9999, 100,  9999, 0   }    // B  → d1/d2/A
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("d1", 0.0, 0.0)
                .depot("d2", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100).startsAt("d1"))
                .vehicle("v2", v -> v.capacity(100).startsAt("d2"))
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .stop("B", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver()
                .timeLimit(Duration.ofSeconds(5))
                .firstSolution(Strategy.PATH_CHEAPEST_ARC)
                .solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.routes()).hasSize(2);

        Route r1 = plan.routes().stream().filter(r -> r.vehicle().equals("v1")).findFirst().orElseThrow();
        Route r2 = plan.routes().stream().filter(r -> r.vehicle().equals("v2")).findFirst().orElseThrow();

        // Each vehicle departs from its own assigned depot.
        assertThat(r1.depotId()).isEqualTo("d1");
        assertThat(r2.depotId()).isEqualTo("d2");

        // Stops are partitioned: A served from d1, B served from d2.
        assertThat(r1.stops()).containsExactly("A");
        assertThat(r2.stops()).containsExactly("B");

        // d1→A→d1 = 200; d2→B→d2 = 200; total 400.
        assertThat(plan.objective()).hasValue(400.0);
    }

    // ── T-R40: A vehicle may start and end at different depots ───────────────

    @Test
    void vehicleStartsAndEndsAtDifferentDepots() {
        // Nodes: 0=d1, 1=d2, 2=A (equidistant from both depots).
        long[][] matrix = {
            {0,    9999, 100},
            {9999, 0,    100},
            {100,  100,  0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("d1", 0.0, 0.0)
                .depot("d2", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100).startsAt("d1").endsAt("d2"))
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver()
                .timeLimit(Duration.ofSeconds(5))
                .firstSolution(Strategy.PATH_CHEAPEST_ARC)
                .solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routes()).hasSize(1);

        // depotId() reports the start depot.
        Route r = plan.routes().get(0);
        assertThat(r.depotId()).isEqualTo("d1");
        assertThat(r.stops()).containsExactly("A");
        // d1→A→d2 = 100 + 100 = 200.
        assertThat(plan.objective()).hasValue(200.0);
    }

    // ── T-R41: Unknown depot id on a vehicle is rejected ─────────────────────

    @Test
    void unknownDepotIdOnVehicleThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("d1", 0.1, 0.1)
                        .depot("d2", 0.2, 0.2)
                        .vehicle("v1", v -> v.capacity(10).startsAt("nope"))
                        .stop("A", s -> s.at(0.15, 0.15).demand(1))
                        .distances(DistanceSource.haversine())
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("unknown depot id");
    }

    // ── T-R42: Duplicate depot id is rejected ───────────────────────────────

    @Test
    void duplicateDepotIdThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("d1", 0.1, 0.1)
                        .depot("d1", 0.2, 0.2)
                        .vehicle("v1", v -> v.capacity(10))
                        .stop("A", s -> s.at(0.15, 0.15).demand(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("Duplicate depot id");
    }

    // ── T-R43: A vehicle with no startsAt uses the default (first) depot ─────

    @Test
    void vehicleWithoutStartsAtUsesDefaultDepot() {
        // Nodes: 0=d1 (default), 1=d2, 2=A. A is cheaper from d1.
        long[][] matrix = {
            {0,   50,  100},
            {50,  0,   200},
            {100, 200, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("d1", 0.0, 0.0)
                .depot("d2", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))   // no startsAt → default depot d1
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver()
                .timeLimit(Duration.ofSeconds(5))
                .firstSolution(Strategy.PATH_CHEAPEST_ARC)
                .solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routes()).hasSize(1);
        assertThat(plan.routes().get(0).depotId()).isEqualTo("d1");
        assertThat(plan.routes().get(0).stops()).containsExactly("A");
    }

    // ── T-R44: Wrong-sized precomputed matrix under multi-depot ──────────────

    @Test
    void wrongSizedMatrixUnderMultiDepotThrowsModelException() {
        // 2 depots + 2 stops = 4 nodes, but matrix is 3×3.
        long[][] tooSmall = {
            {0,   100, 200},
            {100, 0,   150},
            {200, 150, 0  }
        };

        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("d1", 0.0, 0.0)
                        .depot("d2", 0.0, 0.0)
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(0.0, 0.0).demand(1))
                        .stop("B", s -> s.at(0.0, 0.0).demand(1))
                        .distances(DistanceSource.fromMatrix(tooSmall))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("4×4");
    }

    // ── T-R45: Single-depot builder still surfaces depotId on routes ─────────

    @Test
    void singleDepotSurfacesDepotIdOnRoutes() {
        long[][] matrix = {
            {0,   100},
            {100, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("only", 0.0, 0.0)
                .vehicle("v1", v -> v.capacity(100))
                .stop("S", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .goal(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routes()).hasSize(1);
        assertThat(plan.routes().get(0).depotId()).isEqualTo("only");
    }

    // ── T-R46: startsAt with a blank id is rejected ──────────────────────────

    @Test
    void blankStartsAtDepotIdThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("d1", 0.1, 0.1)
                        .vehicle("v1", v -> v.capacity(10).startsAt("  "))
                        .stop("A", s -> s.at(0.15, 0.15).demand(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("non-blank");
    }

    // ── T-R47: time-dependent travel is NOT supported (deferred) ──────────────
    // OR-Tools' native RoutingModel transit callback is (from,to)-only and cannot
    // read the arrival-time variable, so travel time cannot vary by time of day.
    // travelSpeedKmh is a single global scalar. True time-dependent travel would
    // require a CP-SAT routing solver (see io.github.janglada.orkit.routing package-info Limitation).
    @Disabled("time-dependent travel is not supported — see plan, deferred")
    @Test
    void timeDependentTravelIsNotSupported() {
        // Anchor for future work: today the global travelSpeedKmh applies uniformly
        // regardless of departure time, so two identical-distance legs take the same
        // time whether travelled at rush hour or overnight.
        var plan = Optimize.vehicleRouting()
                .depot("D", 0.0, 0.0)
                .travelSpeedKmh(40)
                .vehicle("v1", v -> {})
                .stop("A", s -> s.at(0.0, 0.1).demand(1))
                .stop("B", s -> s.at(0.0, 0.2).demand(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
    }

    // ── T-R38: a stop without .at() is rejected under haversine, accepted with a matrix ──

    @Test
    void stopWithoutAtUnderHaversineThrows() {
        assertThatThrownBy(() -> Optimize.vehicleRouting()
                .depot("D", 41.35, 2.13)
                .vehicle("V", v -> v.capacity(10))
                .stop("A", s -> s.demand(1))            // forgot .at(...)
                .solver())
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("'A'").hasMessageContaining(".at(");
    }

    @Test
    void stopWithoutAtUnderMatrixIsAccepted() {
        long[][] m = {{0, 1000}, {1000, 0}};
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("A", s -> s.demand(1))
                .distances(DistanceSource.fromMatrix(m))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.objective()).hasValue(2000.0);
    }

    // ── T-R39: vehicles(n, cfg) never collides with explicitly named vehicles ──

    @Test
    void vehiclesSkipsTakenGeneratedIds() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("D", 41.35, 2.13)
                .vehicle("vehicle-2", v -> v.capacity(1))
                .vehicles(2, v -> v.capacity(1))          // must become vehicle-1 and vehicle-3
                .stop("A", s -> s.at(41.36, 2.14).demand(1))
                .stop("B", s -> s.at(41.37, 2.15).demand(1))
                .stop("C", s -> s.at(41.38, 2.16).demand(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routes()).extracting(Route::vehicle)
                .containsExactlyInAnyOrder("vehicle-1", "vehicle-2", "vehicle-3");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Routing features: visits, vehicle windows, time matrix, fixed cost, skills, P&D
    // ══════════════════════════════════════════════════════════════════════════

    private static RoutingBuilder bcn() {
        return Optimize.vehicleRouting().depot("Depot", 41.35, 2.13);
    }

    // ── T-R41: visits report arrival within the window and departure = arrival + service ──

    @Test
    void visitsReportArrivalWithinWindow() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(20))
                .stop("A", s -> s.at(41.39, 2.16).demand(4).openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(11, 0))
                        .serviceTime(Duration.ofMinutes(10)))
                .stop("B", s -> s.at(41.38, 2.19).demand(3).openFrom(LocalTime.of(9, 0)).closeAt(LocalTime.of(12, 0))
                        .serviceTime(Duration.ofMinutes(10)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        Route r = plan.routeOf("V").orElseThrow();
        assertThat(r.hasTimes()).isTrue();
        assertThat(r.visits()).hasSize(2);
        for (Visit v : r.visits()) {
            LocalTime open = v.stop().equals("A") ? LocalTime.of(8, 0) : LocalTime.of(9, 0);
            LocalTime close = v.stop().equals("A") ? LocalTime.of(11, 0) : LocalTime.of(12, 0);
            assertThat(v.arrival()).isAfterOrEqualTo(open).isBeforeOrEqualTo(close);
            assertThat(v.departure()).isEqualTo(v.arrival().plusMinutes(10));
        }
        assertThat(r.startTime()).isNotNull();
        assertThat(r.endTime()).isAfter(r.startTime());
        assertThat(r.visits().get(r.visits().size() - 1).loadAfter()).isEqualTo(7);
    }

    // ── T-R42: pure CVRP has visits but no times ────────────────────────────

    @Test
    void visitsHaveNullTimesWithoutTimeFeatures() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(20))
                .stop("A", s -> s.at(41.39, 2.16).demand(4))
                .stop("B", s -> s.at(41.38, 2.19).demand(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        Route r = plan.routeOf("V").orElseThrow();
        assertThat(r.hasTimes()).isFalse();
        assertThat(r.visits()).hasSize(r.stops().size());
        long prev = 0;
        for (Visit v : r.visits()) {
            assertThat(v.arrival()).isNull();
            assertThat(v.departure()).isNull();
            assertThat(v.loadAfter()).isGreaterThanOrEqualTo(prev);
            prev = v.loadAfter();
        }
        assertThat(prev).isEqualTo(r.load());
    }

    // ── T-R43: routeOf is empty for idle and unknown vehicles ───────────────

    @Test
    void routeOfReturnsEmptyForIdleOrUnknownVehicle() {
        RoutingPlan plan = bcn()
                .vehicle("V1", v -> v.capacity(20))
                .vehicle("V2", v -> v.capacity(20))
                .stop("A", s -> s.at(41.39, 2.16).demand(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.vehiclesUsed()).isEqualTo(1);
        String used = plan.routes().get(0).vehicle();
        String idle = used.equals("V1") ? "V2" : "V1";
        assertThat(plan.routeOf(idle)).isEmpty();
        assertThat(plan.routeOf("nope")).isEmpty();
        assertThat(plan.routeOf(used)).isPresent();
    }

    // ── T-R44: availableFrom delays departure and reports times ─────────────

    @Test
    void vehicleAvailableFromDelaysDeparture() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(20).availableFrom(LocalTime.of(9, 0)))
                .stop("A", s -> s.at(41.39, 2.16).demand(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        Route r = plan.routeOf("V").orElseThrow();
        assertThat(r.startTime()).isAfterOrEqualTo(LocalTime.of(9, 0));
        assertThat(r.visits().get(0).arrival()).isAfter(LocalTime.of(9, 0));
    }

    // ── T-R45: maxRouteDuration forces a second vehicle, or drops a stop ────

    @Test
    void maxRouteDurationSplitsOrDropsStops() {
        long[][] meters  = {{0, 1000, 1000}, {1000, 0, 1000}, {1000, 1000, 0}};
        long[][] minutes = {{0, 20, 20}, {20, 0, 20}, {20, 20, 0}};
        RoutingPlan two = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .vehicle("V1", v -> v.maxRouteDuration(Duration.ofMinutes(45)))
                .vehicle("V2", v -> v.maxRouteDuration(Duration.ofMinutes(45)))
                .stop("A", s -> s.demand(1))
                .stop("B", s -> s.demand(1))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(two.isFeasible()).isTrue();
        assertThat(two.vehiclesUsed()).isEqualTo(2);

        RoutingPlan one = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .vehicle("V1", v -> v.maxRouteDuration(Duration.ofMinutes(45)))
                .stop("A", s -> s.demand(1))
                .stop("B", s -> s.demand(1))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .allowDroppingStops(10_000)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(one.isFeasible()).isTrue();
        assertThat(one.unassignedStops()).hasSize(1);
    }

    // ── T-R46: inverted vehicle window is rejected ──────────────────────────

    @Test
    void invertedVehicleWindowThrows() {
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.availableFrom(LocalTime.of(18, 0)).availableUntil(LocalTime.of(8, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("availableFrom");
    }

    // ── T-R54: a supplied time matrix overrides the speed-derived one ───────

    @Test
    void timeMatrixOverridesSpeed() {
        long[][] meters  = {{0, 100}, {100, 0}};
        long[][] minutes = {{0, 600}, {600, 0}};
        RoutingPlan slow = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("A", s -> s.demand(1).closeAt(LocalTime.of(9, 0)))
                .distances(DistanceSource.fromMatrices(meters, minutes))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(slow.isFeasible()).as("10 h of travel cannot reach a stop closing at 09:00").isFalse();

        RoutingPlan fast = Optimize.vehicleRouting()
                .depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(10))
                .stop("A", s -> s.demand(1).closeAt(LocalTime.of(9, 0)))
                .distances(DistanceSource.fromMatrix(meters))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(fast.isFeasible()).isTrue();
    }

    // ── T-R55: mismatched matrices are rejected ─────────────────────────────

    @Test
    void mismatchedMatricesThrow() {
        long[][] m3 = {{0, 1, 1}, {1, 0, 1}, {1, 1, 0}};
        long[][] m2 = {{0, 1}, {1, 0}};
        assertThatThrownBy(() -> DistanceSource.fromMatrices(m3, m2))
                .isInstanceOf(ModelException.class).hasMessageContaining("Time matrix");
    }

    // ── T-R51: fixed cost consolidates work onto one vehicle under totalCost ──

    @Test
    void fixedCostConsolidatesOntoOneVehicle() {
        RoutingPlan plan = bcn()
                .vehicle("V1", v -> v.capacity(100).fixedCost(1_000_000))
                .vehicle("V2", v -> v.capacity(100).fixedCost(1_000_000))
                .stop("A", s -> s.at(41.39, 2.16).demand(1))
                .stop("B", s -> s.at(41.38, 2.19).demand(1))
                .stop("C", s -> s.at(41.40, 2.15).demand(1))
                .stop("D", s -> s.at(41.37, 2.17).demand(1))
                .goal(Goals.totalCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.vehiclesUsed()).isEqualTo(1);
        Route r = plan.routes().get(0);
        assertThat(r.fixedCost()).isEqualTo(1_000_000);
        assertThat(plan.totalFixedCost()).isEqualTo(1_000_000);
        assertThat(plan.objective()).hasValue((double) (r.distanceMeters() + 1_000_000));
    }

    // ── T-R52: vehiclesUsed goal minimizes the count and reports it ─────────

    @Test
    void vehiclesUsedGoalMinimizesCount() {
        RoutingPlan plan = bcn()
                .vehicles(3, v -> v.capacity(100))
                .stop("A", s -> s.at(41.39, 2.16).demand(1))
                .stop("B", s -> s.at(41.38, 2.19).demand(1))
                .stop("C", s -> s.at(41.40, 2.15).demand(1))
                .stop("D", s -> s.at(41.37, 2.17).demand(1))
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.vehiclesUsed()).isEqualTo(1);
        assertThat(plan.objective()).hasValue(1.0);
    }

    // ── T-R53: negative fixed cost is rejected ──────────────────────────────

    @Test
    void negativeFixedCostThrows() {
        assertThatThrownBy(() -> bcn().vehicle("V", v -> v.fixedCost(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("fixedCost");
    }

    // ── T-R56/57: skills route a stop to a capable vehicle, or fail fast ────

    @Test
    void stopRequiringSkillGoesToSkilledVehicle() {
        RoutingPlan plan = bcn()
                .vehicle("Plain", v -> v.capacity(100))
                .vehicle("Reefer", v -> v.capacity(100).skills("cold"))
                .stop("Fish", s -> s.at(41.39, 2.16).demand(1).requires("cold"))
                .stop("Bread", s -> s.at(41.38, 2.19).demand(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routeOf("Reefer").orElseThrow().stops()).contains("Fish");
        assertThat(plan.routeOf("Plain").map(Route::stops).orElse(List.of())).doesNotContain("Fish");
    }

    @Test
    void noVehicleWithRequiredSkillThrows() {
        assertThatThrownBy(() -> bcn()
                .vehicle("Plain", v -> v.capacity(100))
                .stop("Fish", s -> s.at(41.39, 2.16).demand(1).requires("cold"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("cold");
    }

    // ── T-R47: pickup precedes delivery on the same vehicle ─────────────────

    @Test
    void pickupPrecedesDeliveryOnSameVehicle() {
        RoutingPlan plan = bcn()
                .vehicle("V1", v -> v.capacity(10))
                .vehicle("V2", v -> v.capacity(10))
                .stop("P1", s -> s.at(41.39, 2.16).demand(3))
                .stop("D1", s -> s.at(41.42, 2.20))
                .stop("P2", s -> s.at(41.36, 2.10).demand(4))
                .stop("D2", s -> s.at(41.33, 2.05))
                .pickupAndDelivery("P1", "D1")
                .pickupAndDelivery("P2", "D2")
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        for (String[] pair : new String[][]{{"P1", "D1"}, {"P2", "D2"}}) {
            Route r = plan.routes().stream().filter(x -> x.stops().contains(pair[0])).findFirst().orElseThrow();
            assertThat(r.stops()).contains(pair[1]);
            assertThat(r.stops().indexOf(pair[0])).isLessThan(r.stops().indexOf(pair[1]));
            // Load goes up at the pickup and back down at the delivery.
            long atPickup = r.visits().get(r.stops().indexOf(pair[0])).loadAfter();
            long atDelivery = r.visits().get(r.stops().indexOf(pair[1])).loadAfter();
            assertThat(atPickup).isGreaterThan(atDelivery);
        }
    }

    // ── T-R48: an infeasible pair is dropped together ───────────────────────

    @Test
    void pairDroppedTogether() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(2))
                .stop("P", s -> s.at(41.39, 2.16).demand(5))     // exceeds capacity
                .stop("D", s -> s.at(41.42, 2.20))
                .stop("X", s -> s.at(41.38, 2.19).demand(1))
                .pickupAndDelivery("P", "D")
                .allowDroppingStops(1_000_000)   // far above any arc, so only the impossible pair is dropped
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).containsExactlyInAnyOrder("P", "D");
        assertThat(plan.routeOf("V").orElseThrow().stops()).containsExactly("X");
    }

    // ── T-R49: pickup-and-delivery validation ───────────────────────────────

    @Test
    void pickupDeliveryValidation() {
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {})
                .stop("P", s -> s.at(41.39, 2.16).demand(1))
                .pickupAndDelivery("P", "NOPE").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("NOPE");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {})
                .stop("P", s -> s.at(41.39, 2.16).demand(1))
                .pickupAndDelivery("P", "P").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("distinct");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {})
                .stop("P", s -> s.at(41.39, 2.16).demand(1))
                .stop("D1", s -> s.at(41.40, 2.17))
                .stop("D2", s -> s.at(41.41, 2.18))
                .pickupAndDelivery("P", "D1").pickupAndDelivery("P", "D2").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("already belongs");
        assertThatThrownBy(() -> bcn().vehicle("V", v -> {})
                .stop("P", s -> s.at(41.39, 2.16).demand(7))
                .stop("D", s -> s.at(41.40, 2.17).demand(5))
                .pickupAndDelivery("P", "D").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("must equal pickup demand");
    }

    // ── T-R50: LIFO forbids interleaved pairs on one vehicle ────────────────

    @Test
    void lifoPolicyNeverInterleavesPairs() {
        RoutingPlan plan = bcn()
                .vehicle("V", v -> v.capacity(10))
                .stop("P1", s -> s.at(41.39, 2.16).demand(1))
                .stop("D1", s -> s.at(41.42, 2.20))
                .stop("P2", s -> s.at(41.395, 2.165).demand(1))
                .stop("D2", s -> s.at(41.425, 2.205))
                .pickupAndDelivery("P1", "D1")
                .pickupAndDelivery("P2", "D2")
                .pickupAndDeliveryPolicy(PickupDeliveryPolicy.LIFO)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        List<String> seq = plan.routeOf("V").orElseThrow().stops();
        int p1 = seq.indexOf("P1"), d1 = seq.indexOf("D1"), p2 = seq.indexOf("P2"), d2 = seq.indexOf("D2");
        boolean interleaved = (p1 < p2 && p2 < d1 && d1 < d2) || (p2 < p1 && p1 < d2 && d2 < d1);
        assertThat(interleaved).as("LIFO forbids P1 P2 D1 D2 style interleaving, got %s", seq).isFalse();
    }
}

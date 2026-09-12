package opt.routing;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
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
    // require a CP-SAT routing solver (see opt.routing package-info Limitation).
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
}

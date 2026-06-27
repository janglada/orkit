package opt.routing;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        // All stops appear exactly once across all routes
        Set<String> allVisited = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toSet());
        assertThat(allVisited).containsExactlyInAnyOrder("A", "B", "C");

        // No stop appears in two routes simultaneously
        List<String> flatStops = plan.routes().stream()
                .flatMap(r -> r.stops().stream())
                .collect(Collectors.toList());
        assertThat(flatStops).doesNotHaveDuplicates();
    }

    // ── T-R2: Capacity constraint splits stops across two vehicles ───────────

    @Test
    void capacityConstraintRequiresTwoVehicles() {
        // 4 stops × demand 20 each = 80 total; each vehicle holds 45 (fits 2 stops = 40 ≤ 45)
        // → needs exactly 2 vehicles, each carrying 2 stops
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(45))
                .vehicle("v2", v -> v.capacity(45))
                .stop("A", s -> s.at(41.400, 2.165).demand(20))
                .stop("B", s -> s.at(41.385, 2.155).demand(20))
                .stop("C", s -> s.at(41.395, 2.175).demand(20))
                .stop("D", s -> s.at(41.375, 2.162).demand(20))
                .distances(DistanceSource.haversine())
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        // Both vehicles must be active (each carries exactly 2 stops)
        long activeVehicles = plan.routes().stream()
                .filter(r -> !r.stops().isEmpty())
                .count();
        assertThat(activeVehicles).isEqualTo(2);

        // No single route may carry more than 45 units
        for (Route r : plan.routes()) {
            assertThat(r.load())
                    .as("Route for %s exceeds capacity", r.vehicle())
                    .isLessThanOrEqualTo(45);
        }
    }

    // ── T-R3: Time windows respected (earlier window visited first) ───────────

    @Test
    void timeWindowForcesCorrectVisitOrder() {
        // Stops are collocated (identical coordinates) so ordering is driven purely by time windows
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(100))
                .stop("earlyStop",  s -> s.at(41.395, 2.162).demand(1)
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(9, 0)))
                .stop("lateStop",   s -> s.at(41.395, 2.162).demand(1)
                        .openFrom(LocalTime.of(14, 0)).closeAt(LocalTime.of(15, 0)))
                .distances(DistanceSource.haversine())
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();

        List<String> stops = plan.routes().get(0).stops();
        assertThat(stops.indexOf("earlyStop")).isLessThan(stops.indexOf("lateStop"));
    }

    // ── T-R4: Dropping stops when penalty is small ───────────────────────────

    @Test
    void droppedStopsReportedInUnassigned() {
        // 1 vehicle with tiny capacity, 3 stops each with demand 10, penalty too low to force visits
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicle("v1", v -> v.capacity(10)) // only 1 stop fits
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .allowDroppingStops(1) // tiny penalty → solver willingly drops
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        // With capacity 10, only 1 stop can be served; the other 2 must be unassigned
        assertThat(plan.unassignedStops().size()).isGreaterThanOrEqualTo(2);
    }

    // ── T-R5: Precomputed distance matrix respected ───────────────────────────

    @Test
    void precomputedMatrixDistancesUsed() {
        // 2 stops: depot(0), A(1), B(2)
        // Symmetric matrix: depot↔A = 100m, depot↔B = 200m, A↔B = 150m
        long[][] matrix = {
            {0,   100, 200},
            {100, 0,   150},
            {200, 150, 0  }
        };

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 0.0, 0.0)  // coordinates ignored when matrix supplied
                .vehicle("v1", v -> v.capacity(100))
                .stop("A", s -> s.at(0.0, 0.0).demand(1))
                .stop("B", s -> s.at(0.0, 0.0).demand(1))
                .distances(DistanceSource.fromMatrix(matrix))
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        // Minimum tour: depot→A→B→depot = 100+150+200 = 450
        // or depot→B→A→depot = 200+150+100 = 450 (symmetric)
        assertThat(plan.objective()).hasValue(450.0);
    }

    // ── T-R6: Missing depot throws ModelException ────────────────────────────

    @Test
    void missingDepotThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .vehicle("v1", v -> v.capacity(100))
                        .stop("A", s -> s.at(41.400, 2.165).demand(10))
                        .solver()
                        .solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("depot");
    }

    // ── T-R7: No vehicles throws ModelException ──────────────────────────────

    @Test
    void noVehiclesThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.vehicleRouting()
                        .depot("depot", 41.390, 2.160)
                        .stop("A", s -> s.at(41.400, 2.165).demand(10))
                        .solver()
                        .solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-R8: Multiple identical vehicles added via bulk helper ──────────────

    @Test
    void vehiclesBulkHelperCreatesCorrectCount() {
        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("depot", 41.390, 2.160)
                .vehicles(3, v -> v.capacity(50))  // 3 identical vehicles
                .stop("A", s -> s.at(41.400, 2.165).demand(10))
                .stop("B", s -> s.at(41.385, 2.155).demand(10))
                .stop("C", s -> s.at(41.395, 2.175).demand(10))
                .distances(DistanceSource.haversine())
                .minimize(Goals.totalDistance())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        // At most 3 vehicles can be used (we only declared 3)
        assertThat(plan.routes().size()).isLessThanOrEqualTo(3);
    }
}

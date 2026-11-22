package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Multi-dimension capacities, soft deadlines, driver breaks, per-vehicle costs, intermediate
 * solutions, warm starts, and the vehicle-count goal with droppable stops.
 */
class RoutingCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(3);

    /** Depot plus two stops: depot-stop arcs 5 000 m, stop-stop 9 000 m. */
    private static final long[][] V_SHAPE = {{0, 5000, 5000}, {5000, 0, 9000}, {5000, 9000, 0}};

    // ── Vehicle-count goal never trades served stops for vehicles ───────────

    @Test
    void vehiclesUsedServesDroppableStopsWhenItCan() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V1", v -> v.capacity(1)).vehicle("V2", v -> v.capacity(1))
                .stop("S1", s -> s.demand(1)).stop("S2", s -> s.demand(1))
                .distances(DistanceSource.fromMatrix(V_SHAPE))
                .allowDroppingStops(10_000)   // below the cost of opening a vehicle
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.vehiclesUsed()).isEqualTo(2);
        assertThat(plan.objective()).hasValue(2);
    }

    @Test
    void vehiclesUsedStillDropsStopsNoVehicleCanServe() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V1", v -> v.capacity(1)).vehicle("V2", v -> v.capacity(1))
                .stop("S1", s -> s.demand(1)).stop("Big", s -> s.demand(5))
                .distances(DistanceSource.fromMatrix(V_SHAPE))
                .allowDroppingStops(10_000)
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.unassignedStops()).containsExactly("Big");
        assertThat(plan.vehiclesUsed()).isEqualTo(1);
    }

    @Test
    void vehiclesUsedDropBoostOverflowIsRejected() {
        long huge = Long.MAX_VALUE / 8;
        long[][] m = {{0, huge}, {huge, 0}};
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> {}).vehicle("W", v -> {})
                .stop("S", s -> s.penaltyIfDropped(1))
                .distances(DistanceSource.fromMatrix(m))
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(LIMIT).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }

    // ── Several capacity dimensions ─────────────────────────────────────────

    @Test
    void everyCapacityDimensionIsRespected() {
        // Weight alone would fit all three stops on one van; pallets force a second van.
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.38, 2.17)
                .vehicles(3, v -> v.capacity("kg", 100).capacity("pallets", 2))
                .stop("A", s -> s.at(41.39, 2.16).demand("kg", 10).demand("pallets", 1).demand("crates", 4))
                .stop("B", s -> s.at(41.40, 2.18).demand("kg", 20).demand("pallets", 1))
                .stop("C", s -> s.at(41.37, 2.19).demand("kg", 30).demand("pallets", 1))
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).isEmpty();
        assertThat(plan.vehiclesUsed()).isEqualTo(2);
        long kg = 0;
        for (Route r : plan.routes()) {
            assertThat(r.loads().get("pallets")).isLessThanOrEqualTo(2);
            assertThat(r.loads()).containsKeys("kg", "pallets", "crates");
            assertThat(r.load()).as("no demand in the default dimension").isZero();
            kg += r.loads().get("kg");
            Visit last = r.visits().get(r.visits().size() - 1);
            assertThat(last.loadsAfter()).isEqualTo(r.loads());
        }
        assertThat(kg).isEqualTo(60);
    }

    @Test
    void pickupAndDeliveryChecksEveryDimension() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 41.38, 2.17)
                .vehicle("V", v -> {})
                .stop("P", s -> s.at(41.39, 2.16).demand("kg", 7))
                .stop("Q", s -> s.at(41.40, 2.17).demand("kg", 5))
                .pickupAndDelivery("P", "Q").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("dimension 'kg'");

        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.38, 2.17)
                .vehicle("V", v -> v.capacity("kg", 10))
                .stop("P", s -> s.at(41.39, 2.16).demand("kg", 7))
                .stop("Q", s -> s.at(41.40, 2.17))
                .pickupAndDelivery("P", "Q")
                .solver().timeLimit(LIMIT).solve();
        Route r = plan.routes().get(0);
        assertThat(r.stops()).containsExactly("P", "Q");
        assertThat(r.visits().get(0).loadsAfter()).containsEntry("kg", 7L);
        assertThat(r.visits().get(1).loadsAfter()).containsEntry("kg", 0L);
        assertThat(r.loads()).containsEntry("kg", 7L);
    }

    @Test
    void capacityAndDemandArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.capacity(" ", 3)))
                .isInstanceOf(ModelException.class).hasMessageContaining("capacity dimension");
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.capacity("kg", 0)))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> Optimize.vehicleRouting().stop("S", s -> s.demand(null, 3)))
                .isInstanceOf(ModelException.class).hasMessageContaining("demand dimension");
    }

    // ── Soft deadlines ──────────────────────────────────────────────────────

    /** Depot and two stops 60 minutes apart from each other and 30 from the depot. */
    private static final long[][] MINUTES = {{0, 30, 30}, {30, 0, 60}, {30, 60, 0}};

    @Test
    void softDeadlineReordersTheRouteAndReportsLateness() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V", v -> v.availableFrom(LocalTime.of(8, 0)))
                .stop("A", s -> s.lateAfter(LocalTime.of(8, 30), 1))
                .stop("B", s -> s.lateAfter(LocalTime.of(8, 30), 100))
                .distances(DistanceSource.fromMatrices(MINUTES, MINUTES))
                .goal(Goals.totalCost())
                .solver().timeLimit(LIMIT).solve();
        Route r = plan.routes().get(0);
        assertThat(r.stops()).as("the expensive stop goes first").containsExactly("B", "A");
        assertThat(r.visits().get(0).minutesLate()).isZero();
        assertThat(r.visits().get(1).arrival()).isEqualTo(LocalTime.of(9, 30));
        assertThat(r.visits().get(1).minutesLate()).isEqualTo(60);
        assertThat(r.latenessPenalty()).isEqualTo(60);
        assertThat(plan.totalLatenessPenalty()).isEqualTo(60);
        assertThat(plan.objective()).hasValue(r.variableCost() + 60);
    }

    @Test
    void softDeadlineArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().stop("S", s -> s.lateAfter(null, 1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("lateAfter");
        assertThatThrownBy(() -> Optimize.vehicleRouting().stop("S", s -> s.lateAfter(LocalTime.NOON, 0)))
                .isInstanceOf(ModelException.class).hasMessageContaining("penaltyPerMinute");
    }

    // ── Driver breaks ───────────────────────────────────────────────────────

    @Test
    void breaksFallInsideTheirWindowAndNeverDuringService() {
        long[][] minutes = {
                {0, 60, 60, 60},
                {60, 0, 30, 30},
                {60, 30, 0, 30},
                {60, 30, 30, 0}};
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V", v -> v.availableFrom(LocalTime.of(9, 0))
                        .breakBetween(LocalTime.of(11, 0), LocalTime.of(12, 30), Duration.ofMinutes(45)))
                .stop("A", s -> s.serviceTime(Duration.ofMinutes(60)))
                .stop("B", s -> s.serviceTime(Duration.ofMinutes(60)))
                .stop("C", s -> s.serviceTime(Duration.ofMinutes(60)))
                .distances(DistanceSource.fromMatrices(minutes, minutes))
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.isFeasible()).isTrue();
        Route r = plan.routes().get(0);
        assertThat(r.breaks()).hasSize(1);
        DriverBreak b = r.breaks().get(0);
        assertThat(b.start()).isAfterOrEqualTo(LocalTime.of(11, 0));
        assertThat(b.end()).isBeforeOrEqualTo(LocalTime.of(12, 30));
        assertThat(Duration.between(b.start(), b.end())).isEqualTo(Duration.ofMinutes(45));
        for (Visit v : r.visits()) {
            boolean overlaps = b.start().isBefore(v.departure()) && v.arrival().isBefore(b.end());
            assertThat(overlaps).as("break %s overlaps service at %s", b, v.stop()).isFalse();
        }
        // The break pushes the route end past what driving and service alone need (60+30+30+60 + 3×60).
        assertThat(r.endTime()).isAfterOrEqualTo(LocalTime.of(15, 45));
    }

    @Test
    void breakArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.breakBetween(null, LocalTime.NOON, Duration.ofMinutes(5))))
                .isInstanceOf(ModelException.class).hasMessageContaining("null");
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.breakBetween(LocalTime.NOON, LocalTime.of(12, 20), Duration.ofMinutes(30))))
                .isInstanceOf(ModelException.class).hasMessageContaining("does not fit");
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.breakBetween(LocalTime.NOON, LocalTime.of(13, 0), Duration.ZERO)))
                .isInstanceOf(ModelException.class).hasMessageContaining("break duration");
    }

    // ── Per-vehicle variable costs ──────────────────────────────────────────

    @Test
    void totalCostPicksTheCheaperVehiclePerMeter() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("Truck", v -> v.costPerMeter(5))
                .vehicle("Van", v -> v.costPerMeter(2))
                .stop("S1", s -> {}).stop("S2", s -> {})
                .distances(DistanceSource.fromMatrix(V_SHAPE))
                .goal(Goals.totalCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.vehiclesUsed()).isEqualTo(1);
        Route r = plan.routes().get(0);
        assertThat(r.vehicle()).isEqualTo("Van");
        assertThat(r.distanceMeters()).isEqualTo(19_000);
        assertThat(r.variableCost()).isEqualTo(38_000);
        assertThat(plan.totalVariableCost()).isEqualTo(38_000);
        assertThat(plan.objective()).hasValue(38_000);
    }

    @Test
    void costPerMinuteChargesRouteDuration() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V", v -> v.costPerMeter(0).costPerMinute(10))
                .stop("A", s -> {}).stop("B", s -> {})
                .distances(DistanceSource.fromMatrices(MINUTES, MINUTES))
                .goal(Goals.totalCost())
                .solver().timeLimit(LIMIT).solve();
        Route r = plan.routes().get(0);
        long span = Duration.between(r.startTime(), r.endTime()).toMinutes();
        assertThat(span).isEqualTo(120);
        assertThat(r.variableCost()).isEqualTo(1200);
        assertThat(plan.objective()).hasValue(1200);
    }

    @Test
    void otherGoalsMeasurePlainMeters() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V", v -> v.costPerMeter(7))
                .stop("S1", s -> {}).stop("S2", s -> {})
                .distances(DistanceSource.fromMatrix(V_SHAPE))
                .solver().timeLimit(LIMIT).solve();
        assertThat(plan.objective()).hasValue(19_000);
        assertThat(plan.routes().get(0).variableCost()).isEqualTo(133_000);
    }

    @Test
    void costArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.costPerMeter(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("costPerMeter");
        assertThatThrownBy(() -> Optimize.vehicleRouting().vehicle("V", v -> v.costPerMinute(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("costPerMinute");
        long[][] far = {{0, Long.MAX_VALUE / 4}, {Long.MAX_VALUE / 4, 0}};
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.costPerMeter(8)).stop("S", s -> {})
                .distances(DistanceSource.fromMatrix(far)).goal(Goals.totalCost())
                .solver().timeLimit(LIMIT).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }

    // ── Intermediate solutions ──────────────────────────────────────────────

    private static RoutingBuilder grid() {
        RoutingBuilder b = Optimize.vehicleRouting().depot("D", 41.38, 2.17)
                .vehicles(2, v -> v.capacity(6));
        for (int i = 0; i < 10; i++) {
            double lat = 41.36 + 0.005 * (i % 5);
            double lon = 2.14 + 0.01 * (i / 5) + 0.003 * i;
            b.stop("S" + i, s -> s.at(lat, lon).demand(1));
        }
        return b;
    }

    @Test
    void listenerReceivesFeasibleIntermediatePlans() {
        List<RoutingPlan> seen = new ArrayList<>();
        RoutingPlan plan = grid().solver().timeLimit(Duration.ofSeconds(2))
                .improveWith(Metaheuristic.GREEDY_DESCENT)
                .onSolution(seen::add).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(seen).isNotEmpty();
        for (RoutingPlan p : seen) {
            assertThat(p.status()).isEqualTo(SolveStatus.FEASIBLE);
            assertThat(p.unassignedStops()).isEmpty();
            assertThat(p.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(10);
            assertThat(p.objective().orElseThrow()).isGreaterThanOrEqualTo(plan.objective().orElseThrow());
            assertThat(p.diagnostics()).isSameAs(SolveDiagnostics.none());
        }
    }

    @Test
    void listenerExceptionsStopTheSearch() {
        assertThatThrownBy(() -> grid().solver().timeLimit(LIMIT)
                .onSolution(p -> { throw new IllegalStateException("boom"); }).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("onSolution")
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grid().solver().timeLimit(LIMIT)
                .onSolution(p -> { throw new AssertionError("fatal"); }).solve())
                .isInstanceOf(AssertionError.class).hasMessage("fatal");
        assertThatThrownBy(() -> grid().solver().onSolution(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("onSolution");
    }

    @Test
    void listenerMayCancelAndKeepTheFirstPlan() {
        RoutingSolver solver = grid().solver().timeLimit(Duration.ofSeconds(20))
                .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH);
        AtomicReference<RoutingPlan> first = new AtomicReference<>();
        solver.onSolution(p -> {
            first.compareAndSet(null, p);
            solver.cancel();
        });
        RoutingPlan plan = solver.solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.runtime()).isLessThan(Duration.ofSeconds(10));
        assertThat(first.get()).isNotNull();
    }

    @Test
    void intermediatePlansReportTimesAndBreaks() {
        List<RoutingPlan> seen = new ArrayList<>();
        Optimize.vehicleRouting().depot("D", 41.0, 2.0)
                .vehicle("V", v -> v.breakBetween(LocalTime.of(0, 0), LocalTime.of(23, 0), Duration.ofMinutes(10)))
                .stop("A", s -> {}).stop("B", s -> {})
                .distances(DistanceSource.fromMatrices(MINUTES, MINUTES))
                .solver().timeLimit(LIMIT).onSolution(seen::add).solve();
        assertThat(seen).isNotEmpty();
        Route r = seen.get(0).routes().get(0);
        assertThat(r.hasTimes()).isTrue();
        assertThat(r.breaks()).hasSize(1);
        assertThat(r.visits().get(0).arrival()).isNotNull();
    }

    // ── Warm start ──────────────────────────────────────────────────────────

    @Test
    void warmStartReusesPreviousRoutes() {
        RoutingPlan first = grid().solver().timeLimit(Duration.ofSeconds(2)).solve();
        List<RoutingPlan> seen = new ArrayList<>();
        RoutingPlan warmed = grid().solver().timeLimit(Duration.ofSeconds(2))
                .improveWith(Metaheuristic.GREEDY_DESCENT)
                .warmStart(first).onSolution(seen::add).solve();
        assertThat(warmed.isFeasible()).isTrue();
        assertThat(warmed.objective().orElseThrow()).isLessThanOrEqualTo(first.objective().orElseThrow());
        assertThat(seen.get(0).objective().orElseThrow())
                .as("the search starts from the previous routes")
                .isLessThanOrEqualTo(first.objective().orElseThrow());
    }

    @Test
    void warmStartIgnoresStaleIdsAndInfeasibleRoutes() {
        RoutingPlan previous = Optimize.vehicleRouting().depot("D", 41.38, 2.17)
                .vehicle("Old", v -> {}).vehicle("vehicle-1", v -> {})
                .stop("S0", s -> s.at(41.36, 2.14).demand(1))
                .stop("Gone", s -> s.at(41.37, 2.15).demand(1))
                .stop("S1", s -> s.at(41.365, 2.143).demand(1))
                .solver().timeLimit(LIMIT).solve();
        RoutingPlan replanned = grid().solver().timeLimit(LIMIT).warmStart(previous).solve();
        assertThat(replanned.isFeasible()).isTrue();
        assertThat(replanned.unassignedStops()).isEmpty();

        // All ten stops on one van breaks capacity 6: the warm start is rejected, the search starts cold.
        Route overloaded = new Route("vehicle-1", "D",
                List.of("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9"), List.of(),
                0, 10, Map.of(), 0, 0, 0, List.of(), null, null);
        RoutingPlan bad = new RoutingPlan(SolveStatus.FEASIBLE, java.util.OptionalDouble.of(1), Duration.ZERO,
                List.of(overloaded), List.of());
        RoutingPlan cold = grid().solver().timeLimit(LIMIT).warmStart(bad).solve();
        assertThat(cold.isFeasible()).isTrue();
        assertThat(cold.routes()).allSatisfy(r -> assertThat(r.load()).isLessThanOrEqualTo(6));
    }

    @Test
    void warmStartArgumentsAreValidated() {
        assertThatThrownBy(() -> grid().solver().warmStart(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("warmStart");
        RoutingPlan infeasible = new RoutingPlan(SolveStatus.INFEASIBLE, java.util.OptionalDouble.empty(),
                Duration.ZERO, List.of(), List.of());
        assertThatThrownBy(() -> grid().solver().warmStart(infeasible))
                .isInstanceOf(ModelException.class).hasMessageContaining("feasible");
    }
}

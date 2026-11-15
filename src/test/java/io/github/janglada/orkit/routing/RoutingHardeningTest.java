package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Input validation and lifecycle guarantees for routing: coordinate and matrix checks,
 * defensive copies, per-stop drop penalties, positive time limits, and cancellation.
 */
class RoutingHardeningTest {

    private static final long[][] TRIANGLE = {{0, 100, 100}, {100, 0, 100}, {100, 100, 0}};

    @Test
    void depotAtNullIslandIsAValidHaversineLocation() {
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 0.0, 0.0)
                .vehicle("V", v -> {})
                .stop("A", s -> s.at(0.01, 0.01))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.routes().get(0).distanceMeters()).isPositive();
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 91, 0))
                .isInstanceOf(ModelException.class).hasMessageContaining("latitude");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, Double.NaN))
                .isInstanceOf(ModelException.class).hasMessageContaining("longitude");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0).stop("A", s -> s.at(0, 181)))
                .isInstanceOf(ModelException.class).hasMessageContaining("Stop 'A'");
    }

    @Test
    void negativeMatrixEntriesAreRejected() {
        long[][] bad = {{0, -1}, {1, 0}};
        assertThatThrownBy(() -> DistanceSource.fromMatrix(bad))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
        assertThatThrownBy(() -> DistanceSource.fromMatrices(new long[][]{{0, 1}, {1, 0}}, bad))
                .isInstanceOf(ModelException.class).hasMessageContaining("Time matrix");
        assertThatThrownBy(() -> new DistanceSource.PrecomputedMatrix(bad))
                .as("the record constructor validates too, not only the factory")
                .isInstanceOf(ModelException.class);
    }

    @Test
    void matricesAreDefensivelyCopied() {
        long[][] meters = {{0, 100, 100}, {100, 0, 100}, {100, 100, 0}};
        DistanceSource source = DistanceSource.fromMatrix(meters);
        meters[0][1] = 999_999;
        var record = (DistanceSource.PrecomputedMatrix) source;
        assertThat(record.meters()[0][1]).isEqualTo(100);
        record.meters()[0][1] = 777;
        assertThat(record.meters()[0][1]).isEqualTo(100);
    }

    @Test
    void negativeDroppingPenaltyIsRejected() {
        assertThatThrownBy(() -> Optimize.vehicleRouting().allowDroppingStops(-1))
                .isInstanceOf(ModelException.class).hasMessageContaining("allowDroppingStops");
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0).stop("A", s -> s.penaltyIfDropped(0)))
                .isInstanceOf(ModelException.class).hasMessageContaining("penaltyIfDropped");
    }

    @Test
    void perStopDropPenaltyMakesOnlyThatStopOptional() {
        // One vehicle with capacity 1 cannot serve both stops; only B is optional.
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> v.capacity(1))
                .stop("A", s -> s.demand(1))
                .stop("B", s -> s.demand(1).penaltyIfDropped(1_000))
                .distances(DistanceSource.fromMatrix(TRIANGLE))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).containsExactly("B");
    }

    @Test
    void perStopPenaltyOverridesModelWidePenalty() {
        // Serving C costs 200 m extra; the model-wide penalty (10) makes dropping cheaper,
        // but C's own penalty (1,000,000) makes serving it cheaper.
        long[][] m = {{0, 100, 100}, {100, 0, 200}, {100, 200, 0}};
        RoutingPlan plan = Optimize.vehicleRouting().depot("D", 0, 0)
                .vehicle("V", v -> {})
                .stop("B", s -> {})
                .stop("C", s -> s.penaltyIfDropped(1_000_000))
                .distances(DistanceSource.fromMatrix(m))
                .allowDroppingStops(10)
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(plan.isFeasible()).isTrue();
        assertThat(plan.unassignedStops()).containsExactly("B");
    }

    @Test
    void nonPositiveTimeLimitIsRejected() {
        var solver = Optimize.vehicleRouting().depot("D", 0, 0).vehicle("V", v -> {})
                .stop("A", s -> {}).distances(DistanceSource.fromMatrix(new long[][]{{0, 1}, {1, 0}})).solver();
        assertThatThrownBy(() -> solver.timeLimit(Duration.ZERO))
                .isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
        assertThatThrownBy(() -> solver.timeLimit(Duration.ofMillis(-5)))
                .isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
    }

    @Test
    void cancelStopsALongGuidedLocalSearch() throws Exception {
        Random rnd = new Random(42);
        RoutingBuilder b = Optimize.vehicleRouting().depot("D", 41.39, 2.17)
                .vehicles(5, v -> v.capacity(40));
        for (int i = 0; i < 120; i++) {
            double lat = 41.30 + rnd.nextDouble() * 0.2;
            double lon = 2.05 + rnd.nextDouble() * 0.25;
            b.stop("S" + i, s -> s.at(lat, lon).demand(1));
        }
        RoutingSolver solver = b.solver().timeLimit(Duration.ofSeconds(60))
                .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH);
        var future = CompletableFuture.supplyAsync(solver::solve);
        Thread.sleep(1_500);
        solver.cancel();
        RoutingPlan plan = future.get(20, TimeUnit.SECONDS);
        assertThat(plan.runtime().toSeconds()).isLessThan(20);
        assertThat(plan.status()).isIn(SolveStatus.FEASIBLE, SolveStatus.UNKNOWN);
    }

    @Test
    void cancelOnIdleRoutingSolverIsANoOp() {
        RoutingSolver solver = Optimize.vehicleRouting().depot("D", 0, 0).vehicle("V", v -> {})
                .stop("A", s -> {}).distances(DistanceSource.fromMatrix(new long[][]{{0, 1}, {1, 0}}))
                .solver().timeLimit(Duration.ofSeconds(5));
        solver.cancel();
        assertThat(solver.solve().isFeasible()).isTrue();
    }

    @Test
    void vehicleCountGoalOverflowIsReported() {
        long big = Long.MAX_VALUE / 2;
        long[][] m = {{0, big, big}, {big, 0, big}, {big, big, 0}};  // 3 nodes x MAX/2 overflows
        assertThatThrownBy(() -> Optimize.vehicleRouting().depot("D", 0, 0).vehicle("V", v -> {})
                .stop("A", s -> {}).stop("B", s -> {}).distances(DistanceSource.fromMatrix(m))
                .goal(Goals.vehiclesUsed())
                .solver().timeLimit(Duration.ofSeconds(5)).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }
}

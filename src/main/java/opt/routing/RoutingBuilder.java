package opt.routing;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.routing.internal.StopDef;
import opt.routing.internal.VehicleDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Fluent builder for vehicle routing problems. Entry point: {@code Optimize.vehicleRouting()}. */
public final class RoutingBuilder implements OptimizationProblem<RoutingPlan> {

    private String depotId;
    private double depotLat;
    private double depotLon;
    private final List<VehicleDef> vehicles = new ArrayList<>();
    private final List<StopDef> stops       = new ArrayList<>();
    private DistanceSource distanceSource   = DistanceSource.haversine();
    private long droppingPenalty            = 0;
    private RoutingGoal goal                = new RoutingGoal.MinimizeTotalDistance();

    public RoutingBuilder depot(String id, double lat, double lon) {
        this.depotId  = id;
        this.depotLat = lat;
        this.depotLon = lon;
        return this;
    }

    public RoutingBuilder vehicle(String id, Consumer<VehicleConfig> config) {
        VehicleConfig cfg = new VehicleConfig();
        cfg.id = id;
        config.accept(cfg);
        vehicles.add(new VehicleDef(id, cfg.capacity, cfg.maxDistanceMeters));
        return this;
    }

    /** Adds {@code count} identical vehicles configured by the same lambda. */
    public RoutingBuilder vehicles(int count, Consumer<VehicleConfig> config) {
        for (int i = 0; i < count; i++) {
            vehicle("vehicle-" + (vehicles.size() + 1), config);
        }
        return this;
    }

    public RoutingBuilder stop(String id, Consumer<StopConfig> config) {
        StopConfig cfg = new StopConfig();
        cfg.id = id;
        config.accept(cfg);
        stops.add(new StopDef(id, cfg.lat, cfg.lon, cfg.demand,
                cfg.serviceTimeMin, cfg.openMin, cfg.closeMin));
        return this;
    }

    public RoutingBuilder distances(DistanceSource source) {
        this.distanceSource = source;
        return this;
    }

    /**
     * Allows stops to be dropped from routes. The penalty is added to the objective
     * for each unserved stop, so the solver only drops a stop when it is cheaper than serving it.
     */
    public RoutingBuilder allowDroppingStops(long penalty) {
        this.droppingPenalty = penalty;
        return this;
    }

    public RoutingBuilder minimize(RoutingGoal routingGoal) {
        this.goal = routingGoal;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public OptimizationProblem<RoutingPlan> build() {
        validate();
        return new RoutingProblem(snapshot());
    }

    /**
     * Builds the problem and returns a {@link RoutingSolver} (a {@link Solver} subtype)
     * that exposes routing-specific knobs ({@code firstSolution}, {@code improveWith}).
     */
    @Override
    public RoutingSolver solver() {
        validate();
        Snap snap = snapshot();
        return new RoutingSolver(snap.depotId(), snap.depotLat(), snap.depotLon(),
                snap.vehicles(), snap.stops(), snap.distanceSource(), snap.droppingPenalty(), snap.goal());
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void validate() {
        if (depotId == null) throw new ModelException("depot(...) must be called.");
        if (vehicles.isEmpty()) throw new ModelException("At least one vehicle is required.");
        if (stops.isEmpty())    throw new ModelException("At least one stop is required.");
    }

    private Snap snapshot() {
        return new Snap(depotId, depotLat, depotLon,
                List.copyOf(vehicles), List.copyOf(stops),
                distanceSource, droppingPenalty, goal);
    }

    private record Snap(
            String depotId, double depotLat, double depotLon,
            List<VehicleDef> vehicles, List<StopDef> stops,
            DistanceSource distanceSource, long droppingPenalty, RoutingGoal goal
    ) {}

    private static final class RoutingProblem implements OptimizationProblem<RoutingPlan> {

        private final Snap snap;

        RoutingProblem(Snap snap) {
            this.snap = snap;
        }

        @Override
        public RoutingSolver solver() {
            return new RoutingSolver(snap.depotId(), snap.depotLat(), snap.depotLon(),
                    snap.vehicles(), snap.stops(), snap.distanceSource(), snap.droppingPenalty(), snap.goal());
        }
    }
}

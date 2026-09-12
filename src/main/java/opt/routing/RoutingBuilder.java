package opt.routing;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.internal.Validate;
import opt.routing.internal.DepotDef;
import opt.routing.internal.StopDef;
import opt.routing.internal.VehicleDef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for vehicle routing problems. Entry point: {@code Optimize.vehicleRouting()}. */
public final class RoutingBuilder implements OptimizationProblem<RoutingPlan> {

    private final List<DepotDef> depots        = new ArrayList<>();
    private final Set<String> depotIds        = new LinkedHashSet<>();
    private final List<VehicleDef> vehicles = new ArrayList<>();
    private final List<StopDef> stops       = new ArrayList<>();
    private final Set<String> vehicleIds    = new LinkedHashSet<>();
    private final Set<String> stopIds       = new LinkedHashSet<>();
    private final Set<String> stopsWithoutCoordinates = new LinkedHashSet<>();
    private DistanceSource distanceSource   = DistanceSource.haversine();
    private long droppingPenalty            = 0;
    private RoutingGoal goal                = new RoutingGoal.MinimizeTotalDistance();
    private double travelSpeedKmh           = 40.0;

    /**
     * Registers a depot location that vehicles may depart from and return to.
     *
     * <p>This method is accumulative: each call adds another depot, in declaration order.
     * The first registered depot is the default for vehicles that do not specify one
     * via {@link VehicleConfig#startsAt(String)} / {@link VehicleConfig#endsAt(String)}.
     * At least one depot is required.
     *
     * @throws opt.ModelException if {@code id} is blank or a depot with this id was already registered
     */
    public RoutingBuilder depot(String id, double lat, double lon) {
        Validate.nonBlank(id, "depot id");
        if (!depotIds.add(id)) {
            throw new ModelException("Duplicate depot id: '" + id + "'.");
        }
        depots.add(new DepotDef(id, lat, lon));
        return this;
    }

    /** Adds a single vehicle configured by the lambda, which receives a fresh {@link VehicleConfig}. */
    public RoutingBuilder vehicle(String id, Consumer<VehicleConfig> config) {
        Validate.nonBlank(id, "vehicle id");
        if (!vehicleIds.add(id)) {
            throw new ModelException("Duplicate vehicle id: '" + id + "'.");
        }
        VehicleConfig cfg = new VehicleConfig();
        cfg.id = id;
        config.accept(cfg);
        vehicles.add(new VehicleDef(id, cfg.capacity, cfg.maxDistanceMeters,
                cfg.startDepotId, cfg.endDepotId));
        return this;
    }

    /**
     * Adds {@code count} identical vehicles configured by the same lambda. Generated ids are
     * {@code "vehicle-N"} for the smallest N not already taken, so they never collide with
     * ids declared via {@link #vehicle(String, Consumer)}.
     */
    public RoutingBuilder vehicles(int count, Consumer<VehicleConfig> config) {
        int next = 1;
        for (int i = 0; i < count; i++) {
            String id;
            do { id = "vehicle-" + next++; } while (vehicleIds.contains(id));
            vehicle(id, config);
        }
        return this;
    }

    /** Adds a stop (delivery/pickup node) configured by the lambda, which receives a fresh {@link StopConfig}. */
    public RoutingBuilder stop(String id, Consumer<StopConfig> config) {
        Validate.nonBlank(id, "stop id");
        if (!stopIds.add(id)) {
            throw new ModelException("Duplicate stop id: '" + id + "'.");
        }
        StopConfig cfg = new StopConfig();
        cfg.id = id;
        config.accept(cfg);
        if (!cfg.atSet) stopsWithoutCoordinates.add(id);
        stops.add(new StopDef(id, cfg.lat, cfg.lon, cfg.demand,
                cfg.serviceTimeMin, cfg.openMin, cfg.closeMin));
        return this;
    }

    /** Sets how inter-node distances are sourced; defaults to {@link DistanceSource#haversine()}. */
    public RoutingBuilder distances(DistanceSource source) {
        if (source == null) {
            throw new ModelException("distances() must not be null.");
        }
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

    /** Sets the optimization goal; defaults to {@link Goals#totalDistance()}. */
    public RoutingBuilder goal(RoutingGoal routingGoal) {
        if (routingGoal == null) throw new ModelException("goal() must not be null.");
        this.goal = routingGoal;
        return this;
    }

    /**
     * Average vehicle speed used to convert haversine distances to travel times for time-window
     * constraints. Default: 40 km/h. Typical values: urban 20–30, suburban 50, highway 90.
     */
    public RoutingBuilder travelSpeedKmh(double speedKmh) {
        if (speedKmh <= 0) throw new opt.ModelException("travelSpeedKmh must be > 0, got: " + speedKmh);
        this.travelSpeedKmh = speedKmh;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public RoutingProblem build() {
        validate();
        return new RoutingProblemImpl(snapshot());
    }

    /**
     * Builds the problem and returns a {@link RoutingSolver} (a {@link Solver} subtype)
     * that exposes routing-specific knobs ({@code firstSolution}, {@code improveWith}).
     */
    @Override
    public RoutingSolver solver() {
        validate();
        Snap snap = snapshot();
        return new RoutingSolver(snap.depots(),
                snap.vehicles(), snap.stops(), snap.distanceSource(),
                snap.droppingPenalty(), snap.goal(), snap.travelSpeedKmh());
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void validate() {
        if (depots.isEmpty())  throw new ModelException("depot(...) must be called.");
        if (vehicles.isEmpty()) throw new ModelException("At least one vehicle is required.");
        if (stops.isEmpty())    throw new ModelException("At least one stop is required.");
        if (distanceSource instanceof DistanceSource.Haversine && !stopsWithoutCoordinates.isEmpty()) {
            throw new ModelException("Stop '" + stopsWithoutCoordinates.iterator().next()
                    + "' must set coordinates via .at(lat, lon) when using haversine distances.");
        }
    }

    private Snap snapshot() {
        return new Snap(List.copyOf(depots),
                List.copyOf(vehicles), List.copyOf(stops),
                distanceSource, droppingPenalty, goal, travelSpeedKmh);
    }

    private record Snap(
            List<DepotDef> depots,
            List<VehicleDef> vehicles, List<StopDef> stops,
            DistanceSource distanceSource, long droppingPenalty, RoutingGoal goal,
            double travelSpeedKmh
    ) {}

    private static final class RoutingProblemImpl implements RoutingProblem {

        private final Snap snap;

        RoutingProblemImpl(Snap snap) {
            this.snap = snap;
        }

        @Override
        public RoutingSolver solver() {
            return new RoutingSolver(snap.depots(),
                    snap.vehicles(), snap.stops(), snap.distanceSource(),
                    snap.droppingPenalty(), snap.goal(), snap.travelSpeedKmh());
        }
    }
}

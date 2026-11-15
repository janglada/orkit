package io.github.janglada.orkit.routing.internal;

import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.internal.NativeLoader;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.routing.*;

import java.time.LocalTime;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Compiles a routing model description into the OR-Tools Routing library and solves it. */
public final class RoutingCompiler {

    private static final int DAY_MINUTES = 24 * 60;
    private static final String CAPACITY_DIM = "Capacity";
    private static final String TIME_DIM = "Time";
    private static final String DISTANCE_DIM = "Distance";

    private final RoutingSpec spec;
    // Convenience aliases into the spec. Node indices 0..depots.size()-1 are depots.
    private final List<DepotDef> depots;
    private final List<VehicleDef> vehicles;
    private final List<StopDef> stops;
    private final Strategy firstSolution;
    private final Metaheuristic metaheuristic;

    private long timeLimitMillis = 30_000;
    private boolean verbose = false;
    private BooleanSupplier stopRequested = () -> false;

    /**
     * Creates a compiler for the given routing model description.
     *
     * @param spec           the model as declared through the builder
     * @param firstSolution  first-solution strategy
     * @param metaheuristic  local-search metaheuristic
     */
    public RoutingCompiler(RoutingSpec spec, Strategy firstSolution, Metaheuristic metaheuristic) {
        this.spec = spec;
        this.depots = spec.depots();
        this.vehicles = spec.vehicles();
        this.stops = spec.stops();
        this.firstSolution = firstSolution;
        this.metaheuristic = metaheuristic;
    }

    /** Sets the wall-clock time budget for the search; default 30 seconds. */
    public RoutingCompiler timeLimit(java.time.Duration limit) {
        Validate.positive(limit, "timeLimit");
        this.timeLimitMillis = Math.max(1, limit.toMillis());
        return this;
    }

    /** Polled during the search; returning {@code true} stops it and keeps the best routes so far. */
    public RoutingCompiler stopWhen(BooleanSupplier stop) {
        this.stopRequested = Objects.requireNonNull(stop);
        return this;
    }
    /** Enables OR-Tools search log output. */
    public RoutingCompiler verbose(boolean v) { this.verbose = v; return this; }

    /** Everything the search and the extraction need to share about the built model. */
    private record Model(RoutingIndexManager manager, RoutingModel routing, long[][] distMatrix,
                         long[] signedDemands, String[] startDepotIdOf, boolean useTime, boolean useCapacity,
                         Set<String> deliveryStopIds) {}

    /**
     * Builds the OR-Tools {@link RoutingModel}, applies capacity, time-window and distance
     * dimensions, runs the search, and extracts the resulting {@link RoutingPlan}.
     */
    public RoutingPlan solve() {
        NativeLoader.ensureLoaded();
        validate();

        // nodes 0..numDepots-1 = depots (declaration order); numDepots..numDepots+stops-1 = stops
        int numDepots  = depots.size();
        int numNodes   = numDepots + stops.size();
        int numVehicles = vehicles.size();

        long[][] distMatrix = buildDistanceMatrix(numNodes, numDepots);

        // Resolve each vehicle's start/end depot to a node index.
        Map<String, Integer> depotIndexOf = new HashMap<>();
        for (int i = 0; i < numDepots; i++) {
            depotIndexOf.put(depots.get(i).id(), i);
        }
        int[] starts = new int[numVehicles];
        int[] ends = new int[numVehicles];
        String[] startDepotIdOf = new String[numVehicles];
        for (int v = 0; v < numVehicles; v++) {
            VehicleDef vd = vehicles.get(v);
            String startId = vd.startDepotId() != null ? vd.startDepotId()
                    : (vd.endDepotId() != null ? vd.endDepotId() : depots.get(0).id());
            String endId = vd.endDepotId() != null ? vd.endDepotId()
                    : (vd.startDepotId() != null ? vd.startDepotId() : depots.get(0).id());
            starts[v] = resolveDepotIndex(startId, depotIndexOf, vd.id());
            ends[v] = resolveDepotIndex(endId, depotIndexOf, vd.id());
            startDepotIdOf[v] = startId;
        }

        RoutingIndexManager manager = new RoutingIndexManager(numNodes, numVehicles, starts, ends);
        RoutingModel routing        = new RoutingModel(manager);
        try {
            return search(manager, routing, distMatrix, startDepotIdOf);
        } finally {
            // SWIG wrappers hold native (off-heap) memory; the Java GC reclaims it only
            // via finalization, which is deprecated and non-deterministic. Delete
            // explicitly so native memory is freed on every exit path.
            routing.delete();  // also releases registered callbacks and dimensions
            manager.delete();
        }
    }

    /** Builds dimensions/callbacks on the model, runs the search, extracts the plan. */
    private RoutingPlan search(RoutingIndexManager manager, RoutingModel routing,
                               long[][] distMatrix, String[] startDepotIdOf) {
        int numDepots   = depots.size();
        int numNodes    = numDepots + stops.size();
        int numVehicles = vehicles.size();

        // ── Transit model — limitation: NOT time-dependent ──────────────────────
        // OR-Tools' native RoutingModel transit callbacks take only (fromNode, toNode)
        // (optionally vehicle) and CANNOT read the cumul/arrival-time variable during
        // evaluation. Travel time is therefore a function of (from, to) only; a supplied
        // time matrix or the global travelSpeedKmh scalar, never a time-banded profile.
        int transitCb = routing.registerTransitCallback((fromIdx, toIdx) -> {
            int from = manager.indexToNode(fromIdx);
            int to   = manager.indexToNode(toIdx);
            return distMatrix[from][to];
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCb);

        // ── Pickup-and-delivery bookkeeping: deliveries carry negative demand ───
        Map<String, Integer> stopIndexOf = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) stopIndexOf.put(stops.get(i).id(), i);
        Set<String> deliveryStopIds = new HashSet<>();
        long[] signedDemands = new long[numNodes]; // depot demands = 0
        for (int i = 0; i < stops.size(); i++) signedDemands[numDepots + i] = stops.get(i).demand();
        for (PickupDeliveryDef pair : spec.pairs()) {
            int p = stopIndexOf.get(pair.pickupId());
            int d = stopIndexOf.get(pair.deliveryId());
            signedDemands[numDepots + d] = -stops.get(p).demand();
            deliveryStopIds.add(pair.deliveryId());
        }

        // ── Fixed costs (always applied when declared; they shape the search) ───
        long fixedCostBoost = 0;
        if (spec.goal() instanceof RoutingGoal.MinimizeVehiclesUsed) {
            long maxArc = 0;
            for (long[] row : distMatrix) for (long a : row) maxArc = Math.max(maxArc, a);
            // Dominates any single tour's distance.
            fixedCostBoost = Validate.add(Validate.multiply(numNodes, maxArc, "Vehicle-count cost"), 1,
                    "Vehicle-count cost");
        }
        for (int v = 0; v < numVehicles; v++) {
            long fixed = Validate.add(vehicles.get(v).fixedCost(), fixedCostBoost, "Vehicle fixed cost");
            if (fixed > 0) routing.setFixedCostOfVehicle(fixed, v);
        }

        // ── Capacity dimension (only if at least one vehicle has a finite capacity) ──
        boolean useCapacity = vehicles.stream().anyMatch(v -> v.capacity() < Integer.MAX_VALUE);
        if (useCapacity) {
            int demandCb = routing.registerUnaryTransitCallback(idx -> signedDemands[manager.indexToNode(idx)]);
            long[] capacities = vehicles.stream()
                    .mapToLong(v -> v.capacity() == Integer.MAX_VALUE ? Long.MAX_VALUE / 2 : v.capacity()).toArray();
            routing.addDimensionWithVehicleCapacity(demandCb, 0, capacities, true, CAPACITY_DIM);
        }

        // ── Time dimension (any stop window/service, vehicle window/duration cap, or a time matrix) ──
        boolean useTime = useTime();
        if (useTime) {
            long[][] timeMatrix = resolveTimeMatrix(distMatrix);
            int timeCb = routing.registerTransitCallback((fromIdx, toIdx) -> {
                int from = manager.indexToNode(fromIdx);
                int to   = manager.indexToNode(toIdx);
                long service = (from >= numDepots) ? stops.get(from - numDepots).serviceTimeMin() : 0;
                return timeMatrix[from][to] + service;
            });
            // Large slack lets vehicles wait at the depot until a time window opens.
            routing.addDimension(timeCb, DAY_MINUTES, DAY_MINUTES, false, TIME_DIM);
            RoutingDimension timeDim = routing.getMutableDimension(TIME_DIM);
            for (int i = 0; i < stops.size(); i++) {
                StopDef stop = stops.get(i);
                if (stop.hasTimeWindow()) {
                    long nodeIdx = manager.nodeToIndex(numDepots + i);
                    timeDim.cumulVar(nodeIdx).setRange(stop.openMin(), stop.closeMin());
                }
            }
            for (int v = 0; v < numVehicles; v++) {
                VehicleDef vd = vehicles.get(v);
                IntVar startCumul = timeDim.cumulVar(routing.start(v));
                IntVar endCumul = timeDim.cumulVar(routing.end(v));
                if (vd.availableFromMin() > 0) startCumul.setMin(vd.availableFromMin());
                if (vd.availableUntilMin() < DAY_MINUTES) endCumul.setMax(vd.availableUntilMin());
                if (vd.maxRouteDurationMin() > 0) timeDim.setSpanUpperBoundForVehicle(vd.maxRouteDurationMin(), v);
                // Tighten start/end cumuls so the reported times are the earliest consistent ones.
                routing.addVariableMinimizedByFinalizer(startCumul);
                routing.addVariableMinimizedByFinalizer(endCumul);
            }
        }

        // ── Distance dimension: per-vehicle max distance, and ordering for pickup/delivery ──
        boolean useMaxDistance = vehicles.stream().anyMatch(v -> v.maxDistanceMeters() > 0);
        boolean useDistanceDim = useMaxDistance || !spec.pairs().isEmpty();
        if (useDistanceDim) {
            long unbounded = Long.MAX_VALUE / 4;
            long[] perVehicleMaxDist = vehicles.stream()
                    .mapToLong(v -> v.maxDistanceMeters() > 0 ? v.maxDistanceMeters() : unbounded)
                    .toArray();
            routing.addDimensionWithVehicleCapacity(transitCb, 0, perVehicleMaxDist, true, DISTANCE_DIM);
        }

        // ── Optional stops (disjunctions with dropping penalty); pairs drop together ──
        Set<String> pairedStopIds = new HashSet<>();
        for (PickupDeliveryDef pair : spec.pairs()) {
            pairedStopIds.add(pair.pickupId());
            pairedStopIds.add(pair.deliveryId());
        }
        for (int i = 0; i < stops.size(); i++) {
            if (pairedStopIds.contains(stops.get(i).id())) continue;
            long penalty = dropPenaltyOf(stops.get(i));
            if (penalty > 0) routing.addDisjunction(new long[]{manager.nodeToIndex(numDepots + i)}, penalty);
        }

        // ── Pickup and delivery ─────────────────────────────────────────────────
        if (!spec.pairs().isEmpty()) {
            Solver cp = routing.solver();
            RoutingDimension distDim = routing.getMutableDimension(DISTANCE_DIM);
            for (PickupDeliveryDef pair : spec.pairs()) {
                long p = manager.nodeToIndex(numDepots + stopIndexOf.get(pair.pickupId()));
                long d = manager.nodeToIndex(numDepots + stopIndexOf.get(pair.deliveryId()));
                routing.addPickupAndDelivery(p, d);
                cp.addConstraint(cp.makeEquality(routing.vehicleVar(p), routing.vehicleVar(d)));
                cp.addConstraint(cp.makeLessOrEqual(distDim.cumulVar(p), distDim.cumulVar(d)));
                long pairPenalty = Math.max(dropPenaltyOf(stops.get(stopIndexOf.get(pair.pickupId()))),
                        dropPenaltyOf(stops.get(stopIndexOf.get(pair.deliveryId()))));
                if (pairPenalty > 0) {
                    // Penalty is paid per inactive index; both must be active or both inactive.
                    routing.addDisjunction(new long[]{p, d}, pairPenalty, 2);
                    cp.addConstraint(cp.makeEquality(routing.activeVar(p), routing.activeVar(d)));
                }
            }
            switch (spec.pickupDeliveryPolicy()) {
                case FIFO -> routing.setPickupAndDeliveryPolicyOfAllVehicles(RoutingModel.PICKUP_AND_DELIVERY_FIFO);
                case LIFO -> routing.setPickupAndDeliveryPolicyOfAllVehicles(RoutingModel.PICKUP_AND_DELIVERY_LIFO);
                case ANY -> { }
            }
        }

        // ── Vehicle-stop compatibility (skills) ─────────────────────────────────
        for (int i = 0; i < stops.size(); i++) {
            StopDef stop = stops.get(i);
            if (stop.requiredSkills().isEmpty()) continue;
            int[] allowed = java.util.stream.IntStream.range(0, numVehicles)
                    .filter(v -> vehicles.get(v).skills().containsAll(stop.requiredSkills()))
                    .toArray();
            if (allowed.length == 0) {
                throw new ModelException("Stop '" + stop.id() + "' requires skills " + stop.requiredSkills()
                        + " but no vehicle has them.");
            }
            // Prune the node's vehicle variable instead of RoutingModel.setAllowedVehiclesForIndex,
            // whose only binding takes an absl::Span that the Java wrapper cannot construct. Removing
            // the unqualified vehicles is equivalent and, unlike a member constraint over the allowed
            // set, leaves -1 (the "not served" value) in the domain, so the stop can still be dropped.
            Set<Integer> qualified = Arrays.stream(allowed).boxed().collect(Collectors.toSet());
            IntVar vehicleVar = routing.vehicleVar(manager.nodeToIndex(numDepots + i));
            for (int v = 0; v < numVehicles; v++) {
                if (!qualified.contains(v)) vehicleVar.removeValue(v);
            }
        }

        // Cooperative cancellation, polled by the search alongside the time limit.
        routing.addSearchMonitor(routing.solver().makeCustomLimit(stopRequested));

        // Search parameters
        RoutingSearchParameters params = buildSearchParameters();

        long startMs = System.currentTimeMillis();
        Assignment solution = routing.solveWithParameters(params);
        java.time.Duration runtime = java.time.Duration.ofMillis(System.currentTimeMillis() - startMs);
        RoutingSearchStatus.Value routingStatus = routing.status();
        Model model = new Model(manager, routing, distMatrix, signedDemands, startDepotIdOf,
                useTime, useCapacity, deliveryStopIds);
        try {
            return extractPlan(model, solution, routingStatus, runtime);
        } finally {
            if (solution != null) solution.delete();  // native SWIG object
        }
    }

    /** The stop's own drop penalty when set, else the model-wide one; {@code 0} means mandatory. */
    private long dropPenaltyOf(StopDef stop) {
        return stop.dropPenalty() > 0 ? stop.dropPenalty() : Math.max(0, spec.droppingPenalty());
    }

    /** Whether any declared feature needs the time dimension. */
    private boolean useTime() {
        return stops.stream().anyMatch(s -> s.hasTimeWindow() || s.serviceTimeMin() > 0)
                || vehicles.stream().anyMatch(VehicleDef::hasTimeSettings)
                || spec.distanceSource() instanceof DistanceSource.PrecomputedMatrices;
    }

    /** Extracts a pure-Java {@link RoutingPlan} from the native assignment. */
    private RoutingPlan extractPlan(Model m, Assignment solution, RoutingSearchStatus.Value routingStatus,
                                    java.time.Duration runtime) {
        int numDepots   = depots.size();
        int numVehicles = vehicles.size();
        RoutingModel routing = m.routing();
        RoutingIndexManager manager = m.manager();

        if (solution == null) {
            // A cancelled search proves nothing about feasibility.
            SolveStatus status = stopRequested.getAsBoolean() ? SolveStatus.UNKNOWN : mapStatus(routingStatus, false);
            return new RoutingPlan(status, OptionalDouble.empty(), runtime, List.of(), List.of());
        }

        RoutingDimension timeDim = m.useTime() ? routing.getMutableDimension(TIME_DIM) : null;

        List<Route> routes = new ArrayList<>();
        Set<String> visitedStops = new HashSet<>();
        long totalDistance = 0;
        long totalFixed = 0;

        for (int v = 0; v < numVehicles; v++) {
            List<String> routeStops = new ArrayList<>();
            List<Visit> visits = new ArrayList<>();
            long routeDistance = 0;
            int routeLoad = 0;
            int onBoard = 0;
            long index = routing.start(v);
            LocalTime startTime = timeDim == null ? null : minutesToTime(solution.min(timeDim.cumulVar(index)));
            while (!routing.isEnd(index)) {
                int node = manager.indexToNode(index);
                long next = solution.value(routing.nextVar(index));
                routeDistance += routing.getArcCostForVehicle(index, next, (long) v);
                if (node >= numDepots) {
                    StopDef stop = stops.get(node - numDepots);
                    routeStops.add(stop.id());
                    visitedStops.add(stop.id());
                    onBoard += (int) m.signedDemands()[node];
                    if (!m.deliveryStopIds().contains(stop.id())) routeLoad += stop.demand();
                    LocalTime arrival = null, departure = null;
                    if (timeDim != null) {
                        long arrivalMin = solution.min(timeDim.cumulVar(index));
                        arrival = minutesToTime(arrivalMin);
                        departure = minutesToTime(arrivalMin + stop.serviceTimeMin());
                    }
                    visits.add(new Visit(stop.id(), arrival, departure, onBoard));
                }
                index = next;
            }
            LocalTime endTime = timeDim == null ? null : minutesToTime(solution.min(timeDim.cumulVar(index)));
            totalDistance += routeDistance;
            if (!routeStops.isEmpty()) {
                long fixed = vehicles.get(v).fixedCost();
                totalFixed += fixed;
                routes.add(new Route(vehicles.get(v).id(), m.startDepotIdOf()[v],
                        routeStops, visits, routeDistance, routeLoad, fixed, startTime, endTime));
            }
        }

        List<String> unassigned = stops.stream()
                .map(StopDef::id)
                .filter(id -> !visitedStops.contains(id))
                .collect(Collectors.toList());

        double objective = switch (spec.goal()) {
            case RoutingGoal.MinimizeTotalDistance g -> totalDistance;
            case RoutingGoal.MinimizeTotalCost g     -> totalDistance + totalFixed;
            case RoutingGoal.MinimizeVehiclesUsed g  -> routes.size();
        };
        SolveStatus status = mapStatus(routingStatus, true);
        return new RoutingPlan(status, OptionalDouble.of(objective), runtime, routes, unassigned);
    }

    private static LocalTime minutesToTime(long minutes) {
        // Wraps past midnight rather than throwing, for routes that end after 24:00.
        return LocalTime.MIDNIGHT.plusMinutes(minutes);
    }

    /**
     * Maps the OR-Tools routing status to our {@link SolveStatus}. A search that ran out
     * of time before finding any solution is {@code UNKNOWN}, not {@code INFEASIBLE}: the
     * model may well be feasible and the fix is a larger time limit. {@code ROUTING_FAIL}
     * (search exhausted without a solution) is reported as {@code INFEASIBLE}.
     */
    private static SolveStatus mapStatus(RoutingSearchStatus.Value routingStatus, boolean hasSolution) {
        return switch (routingStatus) {
            case ROUTING_OPTIMAL   -> SolveStatus.OPTIMAL;
            case ROUTING_INVALID   -> SolveStatus.MODEL_INVALID;
            case ROUTING_INFEASIBLE -> SolveStatus.INFEASIBLE;
            // Search space exhausted, so no solution exists unless one was nevertheless collected.
            case ROUTING_FAIL      -> hasSolution ? SolveStatus.FEASIBLE : SolveStatus.INFEASIBLE;
            // Otherwise the search either succeeded or simply ran out of time; with nothing to
            // show for it the honest answer is UNKNOWN, since the model may well be feasible.
            default                -> hasSolution ? SolveStatus.FEASIBLE : SolveStatus.UNKNOWN;
        };
    }

    // ── Distance / time matrices ─────────────────────────────────────────────

    private long[][] buildDistanceMatrix(int numNodes, int numDepots) {
        long[][] supplied = switch (spec.distanceSource()) {
            case DistanceSource.PrecomputedMatrix m   -> m.meters();
            case DistanceSource.PrecomputedMatrices m -> m.meters();
            case DistanceSource.Haversine h           -> null;
        };
        if (supplied != null) {
            requireSize(supplied, numNodes, numDepots, "Distance matrix");
            return supplied;
        }
        // Haversine
        double[] lats = new double[numNodes];
        double[] lons = new double[numNodes];
        for (int d = 0; d < numDepots; d++) {
            lats[d] = depots.get(d).lat();
            lons[d] = depots.get(d).lon();
        }
        for (int i = 0; i < stops.size(); i++) {
            lats[numDepots + i] = stops.get(i).lat();
            lons[numDepots + i] = stops.get(i).lon();
        }
        long[][] matrix = new long[numNodes][numNodes];
        for (int i = 0; i < numNodes; i++) {
            for (int j = 0; j < numNodes; j++) {
                matrix[i][j] = haversineMeters(lats[i], lons[i], lats[j], lons[j]);
            }
        }
        return matrix;
    }

    /** Travel-time matrix in minutes: the supplied one when available, else derived from speed. */
    private long[][] resolveTimeMatrix(long[][] distMeters) {
        if (spec.distanceSource() instanceof DistanceSource.PrecomputedMatrices m) {
            requireSize(m.minutes(), distMeters.length, depots.size(), "Time matrix");
            return m.minutes();
        }
        return buildTimeMatrix(distMeters);
    }

    private void requireSize(long[][] mat, int numNodes, int numDepots, String name) {
        if (mat.length != numNodes || Arrays.stream(mat).anyMatch(r -> r.length != numNodes)) {
            throw new ModelException(
                    name + " must be " + numNodes + "×" + numNodes
                            + " (" + numDepots + " depot" + (numDepots == 1 ? "" : "s")
                            + " + " + stops.size() + " stops)");
        }
    }

    /** Converts a distance matrix (meters) to a travel-time matrix (minutes) at {@code travelSpeedKmh}. */
    private long[][] buildTimeMatrix(long[][] distMeters) {
        int n = distMeters.length;
        long[][] time = new long[n][n];
        double speed = spec.travelSpeedKmh() > 0 ? spec.travelSpeedKmh() : 40.0;
        double metersPerMinute = speed * 1000.0 / 60.0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                time[i][j] = Math.max(1, Math.round(distMeters[i][j] / metersPerMinute));
            }
        }
        return time;
    }

    private static long haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return Math.round(R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    // ── Search parameters ────────────────────────────────────────────────────

    private RoutingSearchParameters buildSearchParameters() {
        RoutingSearchParameters.Builder builder = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(mapStrategy(firstSolution))
                .setLocalSearchMetaheuristic(mapMetaheuristic(metaheuristic))
                .setTimeLimit(Duration.newBuilder()
                        .setSeconds(timeLimitMillis / 1000)
                        .setNanos((int) ((timeLimitMillis % 1000) * 1_000_000))
                        .build());
        if (verbose) {
            builder.setLogSearch(true);
        }
        return builder.build();
    }

    private FirstSolutionStrategy.Value mapStrategy(Strategy s) {
        if (s == null) return FirstSolutionStrategy.Value.AUTOMATIC;
        return switch (s) {
            case AUTOMATIC                    -> FirstSolutionStrategy.Value.AUTOMATIC;
            case PATH_CHEAPEST_ARC            -> FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC;
            case PATH_MOST_CONSTRAINED_ARC    -> FirstSolutionStrategy.Value.PATH_MOST_CONSTRAINED_ARC;
            case SAVINGS                      -> FirstSolutionStrategy.Value.SAVINGS;
            case CHRISTOFIDES                 -> FirstSolutionStrategy.Value.CHRISTOFIDES;
            case ALL_UNPERFORMED              -> FirstSolutionStrategy.Value.ALL_UNPERFORMED;
            case BEST_INSERTION               -> FirstSolutionStrategy.Value.BEST_INSERTION;
            case PARALLEL_CHEAPEST_INSERTION  -> FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION;
            case SEQUENTIAL_CHEAPEST_INSERTION-> FirstSolutionStrategy.Value.SEQUENTIAL_CHEAPEST_INSERTION;
            case LOCAL_CHEAPEST_INSERTION     -> FirstSolutionStrategy.Value.LOCAL_CHEAPEST_INSERTION;
            case GLOBAL_CHEAPEST_ARC          -> FirstSolutionStrategy.Value.GLOBAL_CHEAPEST_ARC;
            case LOCAL_CHEAPEST_ARC           -> FirstSolutionStrategy.Value.LOCAL_CHEAPEST_ARC;
            case FIRST_UNBOUND_MIN_VALUE      -> FirstSolutionStrategy.Value.FIRST_UNBOUND_MIN_VALUE;
        };
    }

    private LocalSearchMetaheuristic.Value mapMetaheuristic(Metaheuristic m) {
        if (m == null) return LocalSearchMetaheuristic.Value.AUTOMATIC;
        return switch (m) {
            case AUTOMATIC              -> LocalSearchMetaheuristic.Value.AUTOMATIC;
            case GREEDY_DESCENT         -> LocalSearchMetaheuristic.Value.GREEDY_DESCENT;
            case GUIDED_LOCAL_SEARCH    -> LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH;
            case SIMULATED_ANNEALING    -> LocalSearchMetaheuristic.Value.SIMULATED_ANNEALING;
            case TABU_SEARCH            -> LocalSearchMetaheuristic.Value.TABU_SEARCH;
            case GENERIC_TABU_SEARCH    -> LocalSearchMetaheuristic.Value.GENERIC_TABU_SEARCH;
        };
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private void validate() {
        if (vehicles.isEmpty()) throw new ModelException("At least one vehicle is required.");
        if (stops.isEmpty())    throw new ModelException("At least one stop is required.");
    }

    private int resolveDepotIndex(String depotId, Map<String, Integer> depotIndexOf, String vehicleId) {
        Integer idx = depotIndexOf.get(depotId);
        if (idx == null) {
            throw new ModelException("Vehicle '" + vehicleId
                    + "' references unknown depot id: '" + depotId + "'.");
        }
        return idx;
    }
}

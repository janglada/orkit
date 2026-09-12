package opt.routing.internal;

import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import opt.ModelException;
import opt.SolveStatus;
import opt.internal.NativeLoader;
import opt.routing.*;

import java.util.*;
import java.util.stream.Collectors;

/** Compiles a routing model description into the OR-Tools Routing library and solves it. */
public final class RoutingCompiler {

    // Depots, in declaration order. Node indices 0..depots.size()-1 are depots.
    private final List<DepotDef> depots;

    private final List<VehicleDef> vehicles;
    private final List<StopDef> stops;
    private final DistanceSource distanceSource;
    private final long droppingPenalty;       // <= 0 means dropping not allowed
    private final Strategy firstSolution;
    private final Metaheuristic metaheuristic;
    private final RoutingGoal goal;
    private final double travelSpeedKmh;

    private long timeLimitMillis = 30_000;
    private boolean verbose = false;

    /**
     * Creates a compiler for the given routing model description.
     *
     * @param depots          depot descriptions; node indices {@code 0..depots.size()-1} are depots
     *                        in declaration order. The first depot is the default for vehicles
     *                        that do not specify one.
     * @param vehicles        vehicle descriptions, one per vehicle
     * @param stops           stop descriptions, indexed {@code depots.size()..} after the depots
     * @param distanceSource  how inter-node distances are sourced
     * @param droppingPenalty  per-stop penalty added when a stop is dropped; {@code <= 0} disables dropping
     * @param firstSolution   first-solution strategy
     * @param metaheuristic   local-search metaheuristic
     * @param goal            minimization objective
     * @param travelSpeedKmh  average speed used to convert distances to travel times
     */
    public RoutingCompiler(List<DepotDef> depots,
                           List<VehicleDef> vehicles, List<StopDef> stops,
                           DistanceSource distanceSource, long droppingPenalty,
                           Strategy firstSolution, Metaheuristic metaheuristic,
                           RoutingGoal goal, double travelSpeedKmh) {
        this.depots = List.copyOf(depots);
        this.vehicles = List.copyOf(vehicles);
        this.stops = List.copyOf(stops);
        this.distanceSource = distanceSource;
        this.droppingPenalty = droppingPenalty;
        this.firstSolution = firstSolution;
        this.metaheuristic = metaheuristic;
        this.goal = goal;
        this.travelSpeedKmh = travelSpeedKmh > 0 ? travelSpeedKmh : 40.0;
    }

    /** Sets the wall-clock time budget for the search; default 30 seconds. */
    public RoutingCompiler timeLimit(java.time.Duration limit) {
        if (limit == null) throw new ModelException("timeLimit must not be null.");
        this.timeLimitMillis = limit.toMillis();
        return this;
    }
    /** Enables OR-Tools search log output. */
    public RoutingCompiler verbose(boolean v)                   { this.verbose = v;                           return this; }

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

        // ── Transit model — limitation: NOT time-dependent ──────────────────────
        // OR-Tools' native RoutingModel transit callbacks take only (fromNode, toNode)
        // (optionally vehicle) and CANNOT read the cumul/arrival-time variable during
        // evaluation. The RoutingDimension therefore assumes transit is a function of
        // (from, to, vehicle) only; time-of-day-dependent travel is not first-class.
        // Consequence: travelSpeedKmh (used in buildTimeMatrix below) is a single GLOBAL
        // scalar, not a time-banded profile, so a vehicle is assumed to travel at the
        // same speed regardless of when it departs.
        // Future extension point for true time-dependent travel: a CP-SAT routing
        // solver (the pattern used by every other domain here), where per-arc travel
        // time can depend on the departure-time IntVar via AddElement / interval logic.
        // Arc cost evaluator
        int transitCb = routing.registerTransitCallback((fromIdx, toIdx) -> {
            int from = (int) manager.indexToNode(fromIdx);
            int to   = (int) manager.indexToNode(toIdx);
            return distMatrix[from][to];
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCb);

        // Capacity dimension (only if at least one vehicle has a finite capacity)
        boolean useCapacity = vehicles.stream().anyMatch(v -> v.capacity() < Integer.MAX_VALUE);
        if (useCapacity) {
            long[] demands = new long[numNodes]; // depot demands = 0
            for (int i = 0; i < stops.size(); i++) {
                demands[numDepots + i] = stops.get(i).demand();
            }
            int demandCb = routing.registerUnaryTransitCallback(idx -> demands[(int) manager.indexToNode(idx)]);
            long[] capacities = vehicles.stream().mapToLong(v -> v.capacity() == Integer.MAX_VALUE ? Long.MAX_VALUE / 2 : v.capacity()).toArray();
            routing.addDimensionWithVehicleCapacity(demandCb, 0, capacities, true, "Capacity");
        }

        // Time window dimension (if any stop has a non-trivial time window or service time)
        boolean useTime = stops.stream().anyMatch(s -> s.hasTimeWindow() || s.serviceTimeMin() > 0);
        if (useTime) {
            // Travel speed assumed 40 km/h = 667 m/min; convert distances to minutes
            long[][] timeMatrix = buildTimeMatrix(distMatrix);
            int timeCb = routing.registerTransitCallback((fromIdx, toIdx) -> {
                int from = (int) manager.indexToNode(fromIdx);
                int to   = (int) manager.indexToNode(toIdx);
                long service = (from >= numDepots) ? stops.get(from - numDepots).serviceTimeMin() : 0;
                return timeMatrix[from][to] + service;
            });
            int maxTime = 24 * 60; // 1440 minutes in a day
            // Large slack lets vehicles wait at the depot until a time window opens
            routing.addDimension(timeCb, maxTime, maxTime, false, "Time");
            RoutingDimension timeDim = routing.getMutableDimension("Time");
            for (int i = 0; i < stops.size(); i++) {
                StopDef stop = stops.get(i);
                if (stop.hasTimeWindow()) {
                    long nodeIdx = manager.nodeToIndex(numDepots + i);
                    timeDim.cumulVar(nodeIdx).setRange(stop.openMin(), stop.closeMin());
                }
            }
        }

        // Per-vehicle max distance — enforced via a dedicated distance dimension
        boolean useMaxDistance = vehicles.stream().anyMatch(v -> v.maxDistanceMeters() > 0);
        if (useMaxDistance) {
            long globalMaxDist = vehicles.stream()
                    .mapToLong(v -> v.maxDistanceMeters() > 0 ? v.maxDistanceMeters() : Long.MAX_VALUE / 4)
                    .max().orElse(Long.MAX_VALUE / 4);
            long[] perVehicleMaxDist = vehicles.stream()
                    .mapToLong(v -> v.maxDistanceMeters() > 0 ? v.maxDistanceMeters() : globalMaxDist)
                    .toArray();
            routing.addDimensionWithVehicleCapacity(transitCb, 0, perVehicleMaxDist, true, "Distance");
        }

        // Optional stops (disjunctions with dropping penalty)
        if (droppingPenalty > 0) {
            for (int i = 0; i < stops.size(); i++) {
                routing.addDisjunction(new long[]{manager.nodeToIndex(numDepots + i)}, droppingPenalty);
            }
        }

        // Search parameters
        RoutingSearchParameters params = buildSearchParameters();

        long startMs = System.currentTimeMillis();
        Assignment solution = routing.solveWithParameters(params);
        java.time.Duration runtime = java.time.Duration.ofMillis(System.currentTimeMillis() - startMs);
        int routingStatus = routing.status();
        try {
            return extractPlan(manager, routing, solution, routingStatus, runtime, startDepotIdOf);
        } finally {
            if (solution != null) solution.delete();  // native SWIG object
        }
    }

    /** Extracts a pure-Java {@link RoutingPlan} from the native assignment. */
    private RoutingPlan extractPlan(RoutingIndexManager manager, RoutingModel routing,
                                    Assignment solution, int routingStatus,
                                    java.time.Duration runtime, String[] startDepotIdOf) {
        int numDepots   = depots.size();
        int numVehicles = vehicles.size();

        if (solution == null) {
            SolveStatus status = mapStatus(routingStatus, false);
            return new RoutingPlan(status, OptionalDouble.empty(), runtime,
                    List.of(), List.of());
        }

        // Extract routes
        List<Route> routes = new ArrayList<>();
        Set<String> visitedStops = new HashSet<>();
        long totalDistance = 0;

        for (int v = 0; v < numVehicles; v++) {
            List<String> routeStops = new ArrayList<>();
            long routeDistance = 0;
            int routeLoad = 0;
            long index = routing.start(v);
            while (!routing.isEnd(index)) {
                int node = (int) manager.indexToNode(index);
                long next = solution.value(routing.nextVar(index));
                routeDistance += routing.getArcCostForVehicle(index, next, (long) v);
                index = next;
                if (node >= numDepots) {
                    StopDef stop = stops.get(node - numDepots);
                    routeStops.add(stop.id());
                    routeLoad += stop.demand();
                    visitedStops.add(stop.id());
                }
            }
            totalDistance += routeDistance;
            if (!routeStops.isEmpty()) {
                routes.add(new Route(vehicles.get(v).id(), startDepotIdOf[v],
                        routeStops, routeDistance, routeLoad));
            }
        }

        List<String> unassigned = stops.stream()
                .map(StopDef::id)
                .filter(id -> !visitedStops.contains(id))
                .collect(Collectors.toList());

        SolveStatus status = mapStatus(routingStatus, true);
        return new RoutingPlan(status, OptionalDouble.of(totalDistance), runtime, routes, unassigned);
    }

    /** Maps the OR-Tools routing status to our {@link SolveStatus}. */
    private static SolveStatus mapStatus(int routingStatus, boolean hasSolution) {
        if (routingStatus == RoutingModel.ROUTING_OPTIMAL)    return SolveStatus.OPTIMAL;
        if (routingStatus == RoutingModel.ROUTING_INFEASIBLE) return SolveStatus.INFEASIBLE;
        if (hasSolution) return SolveStatus.FEASIBLE;
        // No solution found.
        if (routingStatus == RoutingModel.ROUTING_FAIL
                || routingStatus == RoutingModel.ROUTING_FAIL_TIMEOUT) return SolveStatus.INFEASIBLE;
        return SolveStatus.UNKNOWN;
    }

    // ── Distance matrix ─────────────────────────────────────────────────────

    private long[][] buildDistanceMatrix(int numNodes, int numDepots) {
        if (distanceSource instanceof DistanceSource.PrecomputedMatrix m) {
            long[][] mat = m.meters();
            if (mat.length != numNodes || Arrays.stream(mat).anyMatch(r -> r.length != numNodes)) {
                throw new ModelException(
                        "Distance matrix must be " + numNodes + "×" + numNodes
                                + " (" + numDepots + " depot" + (numDepots == 1 ? "" : "s")
                                + " + " + stops.size() + " stops)");
            }
            return mat;
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

    /** Converts a distance matrix (meters) to a travel-time matrix (minutes) at {@code travelSpeedKmh}. */
    private long[][] buildTimeMatrix(long[][] distMeters) {
        int n = distMeters.length;
        long[][] time = new long[n][n];
        // metersPerMinute = km/h × 1000 / 60
        double metersPerMinute = travelSpeedKmh * 1000.0 / 60.0;
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
            case SWEEP                        -> FirstSolutionStrategy.Value.SWEEP;
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
        if (distanceSource instanceof DistanceSource.Haversine) {
            for (DepotDef d : depots) {
                if (d.lat() == 0 && d.lon() == 0) {
                    throw new ModelException("Depot coordinates must be set when using haversine distances.");
                }
            }
        }
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

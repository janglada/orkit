package opt.routing.internal;

import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import opt.ModelException;
import opt.SolveStatus;
import opt.routing.*;

import java.util.*;
import java.util.stream.Collectors;

/** Compiles a routing model description into the OR-Tools Routing library and solves it. */
public final class RoutingCompiler {

    // Depot lat/lon
    private final String depotId;
    private final double depotLat;
    private final double depotLon;

    private final List<VehicleDef> vehicles;
    private final List<StopDef> stops;
    private final DistanceSource distanceSource;
    private final long droppingPenalty;       // <= 0 means dropping not allowed
    private final Strategy firstSolution;
    private final Metaheuristic metaheuristic;
    private final RoutingGoal goal;

    private long timeLimitSeconds = 30;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    public RoutingCompiler(String depotId, double depotLat, double depotLon,
                           List<VehicleDef> vehicles, List<StopDef> stops,
                           DistanceSource distanceSource, long droppingPenalty,
                           Strategy firstSolution, Metaheuristic metaheuristic,
                           RoutingGoal goal) {
        this.depotId = depotId;
        this.depotLat = depotLat;
        this.depotLon = depotLon;
        this.vehicles = List.copyOf(vehicles);
        this.stops = List.copyOf(stops);
        this.distanceSource = distanceSource;
        this.droppingPenalty = droppingPenalty;
        this.firstSolution = firstSolution;
        this.metaheuristic = metaheuristic;
        this.goal = goal;
    }

    public RoutingCompiler timeLimit(java.time.Duration limit) { this.timeLimitSeconds = limit.toSeconds(); return this; }
    public RoutingCompiler workers(int n)                       { this.workers = n;                           return this; }
    public RoutingCompiler seed(long s)                         { this.seed = s;                              return this; }
    public RoutingCompiler verbose(boolean v)                   { this.verbose = v;                           return this; }

    public RoutingPlan solve() {
        validate();

        // node 0 = depot, nodes 1..N = stops
        int numNodes    = 1 + stops.size();
        int numVehicles = vehicles.size();

        long[][] distMatrix = buildDistanceMatrix(numNodes);

        RoutingIndexManager manager = new RoutingIndexManager(numNodes, numVehicles, 0);
        RoutingModel routing        = new RoutingModel(manager);

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
            long[] demands = new long[numNodes]; // depot demand = 0
            for (int i = 0; i < stops.size(); i++) {
                demands[i + 1] = stops.get(i).demand();
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
                long service = (from > 0) ? stops.get(from - 1).serviceTimeMin() : 0;
                return timeMatrix[from][to] + service;
            });
            int maxTime = 24 * 60; // 1440 minutes in a day
            // Large slack lets vehicles wait at the depot until a time window opens
            routing.addDimension(timeCb, maxTime, maxTime, false, "Time");
            RoutingDimension timeDim = routing.getMutableDimension("Time");
            for (int i = 0; i < stops.size(); i++) {
                StopDef stop = stops.get(i);
                if (stop.hasTimeWindow()) {
                    long nodeIdx = manager.nodeToIndex(i + 1);
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
                routing.addDisjunction(new long[]{manager.nodeToIndex(i + 1)}, droppingPenalty);
            }
        }

        // Search parameters
        RoutingSearchParameters params = buildSearchParameters();

        long startMs = System.currentTimeMillis();
        Assignment solution = routing.solveWithParameters(params);
        java.time.Duration runtime = java.time.Duration.ofMillis(System.currentTimeMillis() - startMs);

        if (solution == null) {
            return new RoutingPlan(SolveStatus.INFEASIBLE, OptionalDouble.empty(), runtime,
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
                if (node > 0) {
                    StopDef stop = stops.get(node - 1);
                    routeStops.add(stop.id());
                    routeLoad += stop.demand();
                    visitedStops.add(stop.id());
                }
            }
            totalDistance += routeDistance;
            if (!routeStops.isEmpty()) {
                routes.add(new Route(vehicles.get(v).id(), routeStops, routeDistance, routeLoad));
            }
        }

        List<String> unassigned = stops.stream()
                .map(StopDef::id)
                .filter(id -> !visitedStops.contains(id))
                .collect(Collectors.toList());

        SolveStatus status = SolveStatus.FEASIBLE;
        return new RoutingPlan(status, OptionalDouble.of(totalDistance), runtime, routes, unassigned);
    }

    // ── Distance matrix ─────────────────────────────────────────────────────

    private long[][] buildDistanceMatrix(int numNodes) {
        if (distanceSource instanceof DistanceSource.PrecomputedMatrix m) {
            return m.meters();
        }
        // Haversine
        double[] lats = new double[numNodes];
        double[] lons = new double[numNodes];
        lats[0] = depotLat;
        lons[0] = depotLon;
        for (int i = 0; i < stops.size(); i++) {
            lats[i + 1] = stops.get(i).lat();
            lons[i + 1] = stops.get(i).lon();
        }
        long[][] matrix = new long[numNodes][numNodes];
        for (int i = 0; i < numNodes; i++) {
            for (int j = 0; j < numNodes; j++) {
                matrix[i][j] = haversineMeters(lats[i], lons[i], lats[j], lons[j]);
            }
        }
        return matrix;
    }

    /** Converts a distance matrix (meters) to a travel-time matrix (minutes) at 40 km/h. */
    private long[][] buildTimeMatrix(long[][] distMeters) {
        int n = distMeters.length;
        long[][] time = new long[n][n];
        // 40 km/h = 40000 m / 60 min = 667 m/min
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                time[i][j] = Math.max(1, distMeters[i][j] / 667);
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
                .setTimeLimit(Duration.newBuilder().setSeconds(timeLimitSeconds).build());
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
        if (depotLat == 0 && depotLon == 0 && distanceSource instanceof DistanceSource.Haversine) {
            throw new ModelException("Depot coordinates must be set when using haversine distances.");
        }
    }
}

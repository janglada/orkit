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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Compiles a routing model description into the OR-Tools Routing library and solves it. */
public final class RoutingCompiler {

    private static final int DAY_MINUTES = 24 * 60;
    private static final String CAPACITY_DIM_PREFIX = "Capacity:";
    private static final String TIME_DIM = "Time";
    private static final String DISTANCE_DIM = "Distance";

    private final RoutingSpec spec;
    private final Strategy firstSolution;
    private final Metaheuristic metaheuristic;

    private long timeLimitMillis = 30_000;
    private boolean verbose = false;
    private BooleanSupplier stopRequested = () -> false;
    private Consumer<RoutingPlan> listener;
    private RoutingPlan warmStart;
    private final AtomicReference<Throwable> listenerFailure = new AtomicReference<>();

    /**
     * Creates a compiler for the given routing model description.
     *
     * @param spec           the model as declared through the builder
     * @param firstSolution  first-solution strategy
     * @param metaheuristic  local-search metaheuristic
     */
    public RoutingCompiler(RoutingSpec spec, Strategy firstSolution, Metaheuristic metaheuristic) {
        this.spec = spec;
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

    /** Receives every solution the search accepts; {@code null} for none. */
    public RoutingCompiler onSolution(Consumer<RoutingPlan> onSolution) {
        this.listener = onSolution;
        return this;
    }

    /** Routes to start the search from; {@code null} for a regular first-solution strategy. */
    public RoutingCompiler warmStart(RoutingPlan previous) {
        this.warmStart = previous;
        return this;
    }

    /**
     * Builds the OR-Tools {@link RoutingModel}, applies capacity, time-window and distance
     * dimensions, runs the search, and extracts the resulting {@link RoutingPlan}.
     */
    public RoutingPlan solve() {
        NativeLoader.ensureLoaded();
        if (spec.vehicles().isEmpty()) throw new ModelException("At least one vehicle is required.");
        if (spec.stops().isEmpty())    throw new ModelException("At least one stop is required.");
        Network net = Network.of(spec);

        RoutingIndexManager manager = new RoutingIndexManager(net.numNodes, net.numVehicles, net.starts, net.ends);
        RoutingModel routing = new RoutingModel(manager);
        try {
            return search(net, manager, routing);
        } finally {
            // SWIG wrappers hold native (off-heap) memory; the Java GC reclaims it only
            // via finalization, which is deprecated and non-deterministic. Delete
            // explicitly so native memory is freed on every exit path.
            routing.delete();  // also releases registered callbacks, dimensions and breaks
            manager.delete();
        }
    }

    /** Builds the model on {@code routing}, runs the search, extracts the plan. */
    private RoutingPlan search(Network net, RoutingIndexManager manager, RoutingModel routing) {
        Built built = ModelBuilder.build(spec, net, manager, routing);
        BooleanSupplier stop = () -> stopRequested.getAsBoolean() || listenerFailure.get() != null;
        PlanReader reader = new PlanReader(spec, net, manager, routing, built);

        // Cooperative cancellation, polled by the search alongside the time limit.
        routing.addSearchMonitor(routing.solver().makeCustomLimit(stop));
        long startNs = System.nanoTime();
        if (listener != null) {
            routing.addAtSolutionCallback(() -> {
                if (listenerFailure.get() != null) return;
                try {
                    java.time.Duration elapsed = java.time.Duration.ofNanos(System.nanoTime() - startNs);
                    listener.accept(reader.read(reader.live(), SolveStatus.FEASIBLE, elapsed));
                } catch (Throwable t) {
                    listenerFailure.compareAndSet(null, t);
                }
            });
        }

        RoutingSearchParameters params = SearchParams.of(firstSolution, metaheuristic, timeLimitMillis, verbose);
        Assignment initial = null;
        Assignment solution = null;
        try {
            if (warmStart != null) {
                routing.closeModelWithParameters(params);
                long[][] routes = WarmStart.routes(warmStart, spec, net, manager);
                // Null when the previous routes are infeasible here: the search then starts cold.
                initial = routing.readAssignmentFromRoutes(routes, true);
            }
            solution = routing.solveFromAssignmentWithParameters(initial, params);
            java.time.Duration runtime = java.time.Duration.ofNanos(System.nanoTime() - startNs);
            RoutingSearchStatus.Value routingStatus = routing.status();
            rethrowListenerFailure();
            if (solution == null) {
                // A cancelled search proves nothing about feasibility.
                SolveStatus status = stop.getAsBoolean() ? SolveStatus.UNKNOWN : mapStatus(routingStatus, false);
                return new RoutingPlan(status, OptionalDouble.empty(), runtime, List.of(), List.of());
            }
            return reader.read(reader.of(solution), mapStatus(routingStatus, true), runtime);
        } finally {
            // delete() frees only objects Java owns; model-owned assignments are left alone.
            if (solution != null) solution.delete();
            if (initial != null) initial.delete();
        }
    }

    private void rethrowListenerFailure() {
        Throwable failure = listenerFailure.get();
        if (failure == null) return;
        if (failure instanceof Error e) throw e;
        throw new ModelException("onSolution listener threw an exception; search was stopped.", failure);
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

    private static LocalTime minutesToTime(long minutes) {
        // Wraps past midnight rather than throwing, for routes that end after 24:00.
        return LocalTime.MIDNIGHT.plusMinutes(minutes);
    }

    // ── Pure-Java view of the network ───────────────────────────────────────

    /**
     * Node layout, matrices and per-dimension demands derived from the spec. Node indices
     * {@code 0..numDepots-1} are depots in declaration order, then the stops.
     */
    private static final class Network {
        final int numDepots;
        final int numNodes;
        final int numVehicles;
        final long[][] dist;
        final long maxArc;
        final int[] starts;
        final int[] ends;
        final String[] startDepotIdOf;
        final Map<String, Integer> stopIndexOf = new HashMap<>();
        final Set<String> deliveryStopIds = new HashSet<>();
        /** Every dimension any stop or vehicle mentions, sorted. */
        final List<String> dimensions;
        /** Dimensions in which at least one vehicle is capacitated, sorted. */
        final List<String> capacityDimensions;
        /** Per dimension, demand by node with pair deliveries negated. */
        final Map<String, long[]> signedDemands = new HashMap<>();
        final boolean useTime;

        private Network(RoutingSpec spec) {
            List<DepotDef> depots = spec.depots();
            List<StopDef> stops = spec.stops();
            List<VehicleDef> vehicles = spec.vehicles();
            numDepots = depots.size();
            numNodes = numDepots + stops.size();
            numVehicles = vehicles.size();
            for (int i = 0; i < stops.size(); i++) stopIndexOf.put(stops.get(i).id(), i);

            dist = distanceMatrix(spec);
            long longest = 0;
            for (long[] row : dist) for (long a : row) longest = Math.max(longest, a);
            maxArc = longest;

            Map<String, Integer> depotIndexOf = new HashMap<>();
            for (int i = 0; i < numDepots; i++) depotIndexOf.put(depots.get(i).id(), i);
            starts = new int[numVehicles];
            ends = new int[numVehicles];
            startDepotIdOf = new String[numVehicles];
            for (int v = 0; v < numVehicles; v++) {
                VehicleDef vd = vehicles.get(v);
                String startId = vd.startDepotId() != null ? vd.startDepotId()
                        : (vd.endDepotId() != null ? vd.endDepotId() : depots.get(0).id());
                String endId = vd.endDepotId() != null ? vd.endDepotId()
                        : (vd.startDepotId() != null ? vd.startDepotId() : depots.get(0).id());
                starts[v] = depotIndex(startId, depotIndexOf, vd.id());
                ends[v] = depotIndex(endId, depotIndexOf, vd.id());
                startDepotIdOf[v] = startId;
            }

            SortedSet<String> dims = new TreeSet<>();
            SortedSet<String> capDims = new TreeSet<>();
            for (StopDef s : stops) dims.addAll(s.demands().keySet());
            for (VehicleDef v : vehicles) capDims.addAll(v.capacities().keySet());
            dims.addAll(capDims);
            dimensions = List.copyOf(dims);
            capacityDimensions = List.copyOf(capDims);

            for (PickupDeliveryDef pair : spec.pairs()) deliveryStopIds.add(pair.deliveryId());
            for (String d : dimensions) {
                long[] signed = new long[numNodes]; // depot demands = 0
                for (int i = 0; i < stops.size(); i++) signed[numDepots + i] = stops.get(i).demand(d);
                for (PickupDeliveryDef pair : spec.pairs()) {
                    signed[numDepots + stopIndexOf.get(pair.deliveryId())] =
                            -stops.get(stopIndexOf.get(pair.pickupId())).demand(d);
                }
                signedDemands.put(d, signed);
            }

            useTime = stops.stream().anyMatch(s -> s.hasTimeWindow() || s.serviceTimeMin() > 0 || s.hasSoftWindow())
                    || vehicles.stream().anyMatch(VehicleDef::hasTimeSettings)
                    || spec.distanceSource() instanceof DistanceSource.PrecomputedMatrices;
        }

        static Network of(RoutingSpec spec) {
            return new Network(spec);
        }

        private static int depotIndex(String depotId, Map<String, Integer> depotIndexOf, String vehicleId) {
            Integer idx = depotIndexOf.get(depotId);
            if (idx == null) {
                throw new ModelException("Vehicle '" + vehicleId
                        + "' references unknown depot id: '" + depotId + "'.");
            }
            return idx;
        }

        private static long[][] distanceMatrix(RoutingSpec spec) {
            int numDepots = spec.depots().size();
            int numNodes = numDepots + spec.stops().size();
            long[][] supplied = switch (spec.distanceSource()) {
                case DistanceSource.PrecomputedMatrix m   -> m.meters();
                case DistanceSource.PrecomputedMatrices m -> m.meters();
                case DistanceSource.Haversine h           -> null;
            };
            if (supplied != null) {
                requireSize(supplied, numNodes, numDepots, spec.stops().size(), "Distance matrix");
                return supplied;
            }
            double[] lats = new double[numNodes];
            double[] lons = new double[numNodes];
            for (int d = 0; d < numDepots; d++) {
                lats[d] = spec.depots().get(d).lat();
                lons[d] = spec.depots().get(d).lon();
            }
            for (int i = 0; i < spec.stops().size(); i++) {
                lats[numDepots + i] = spec.stops().get(i).lat();
                lons[numDepots + i] = spec.stops().get(i).lon();
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
        long[][] timeMatrix(RoutingSpec spec) {
            if (spec.distanceSource() instanceof DistanceSource.PrecomputedMatrices m) {
                requireSize(m.minutes(), numNodes, numDepots, numNodes - numDepots, "Time matrix");
                return m.minutes();
            }
            long[][] time = new long[numNodes][numNodes];
            double speed = spec.travelSpeedKmh() > 0 ? spec.travelSpeedKmh() : 40.0;
            double metersPerMinute = speed * 1000.0 / 60.0;
            for (int i = 0; i < numNodes; i++) {
                for (int j = 0; j < numNodes; j++) {
                    time[i][j] = Math.max(1, Math.round(dist[i][j] / metersPerMinute));
                }
            }
            return time;
        }

        private static void requireSize(long[][] mat, int numNodes, int numDepots, int numStops, String name) {
            if (mat.length != numNodes || Arrays.stream(mat).anyMatch(r -> r.length != numNodes)) {
                throw new ModelException(
                        name + " must be " + numNodes + "×" + numNodes
                                + " (" + numDepots + " depot" + (numDepots == 1 ? "" : "s")
                                + " + " + numStops + " stops)");
            }
        }

        private static long haversineMeters(double lat1, double lon1, double lat2, double lon2) {
            final double r = 6_371_000.0; // Earth radius in meters
            double dLat = Math.toRadians(lat2 - lat1);
            double dLon = Math.toRadians(lon2 - lon1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                     + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                     * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return Math.round(r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
        }
    }

    // ── Model construction ──────────────────────────────────────────────────

    /** What the extraction needs from the built model. */
    private record Built(RoutingDimension timeDim, IntervalVar[][] breaks) {}

    /** Adds costs, dimensions, disjunctions, pairs, skills and breaks to a fresh {@link RoutingModel}. */
    private static final class ModelBuilder {

        static Built build(RoutingSpec spec, Network net, RoutingIndexManager manager, RoutingModel routing) {
            List<VehicleDef> vehicles = spec.vehicles();
            List<StopDef> stops = spec.stops();
            boolean costGoal = spec.goal() instanceof RoutingGoal.MinimizeTotalCost;

            // ── Transit model — limitation: NOT time-dependent ──────────────────
            // OR-Tools' native RoutingModel transit callbacks take only (fromNode, toNode)
            // and CANNOT read the cumul/arrival-time variable during evaluation. Travel time
            // is therefore a function of (from, to) only.
            long[][] dist = net.dist;
            int metersCb = routing.registerTransitCallback(
                    (fromIdx, toIdx) -> dist[manager.indexToNode(fromIdx)][manager.indexToNode(toIdx)]);
            setArcCosts(spec, net, manager, routing, metersCb, costGoal);

            // ── Fixed costs (always applied when declared; they shape the search) ──
            long vehicleBoost = vehiclesUsedBoost(spec, net);
            for (int v = 0; v < net.numVehicles; v++) {
                long fixed = Validate.add(vehicles.get(v).fixedCost(), vehicleBoost, "Vehicle fixed cost");
                if (fixed > 0) routing.setFixedCostOfVehicle(fixed, v);
            }

            // ── Capacity dimensions, one per dimension some vehicle caps ────────
            for (String d : net.capacityDimensions) {
                long[] signed = net.signedDemands.get(d);
                int demandCb = routing.registerUnaryTransitCallback(idx -> signed[manager.indexToNode(idx)]);
                long[] capacities = vehicles.stream()
                        .mapToLong(v -> v.capacities().getOrDefault(d, Long.MAX_VALUE / 2)).toArray();
                routing.addDimensionWithVehicleCapacity(demandCb, 0, capacities, true, CAPACITY_DIM_PREFIX + d);
            }

            RoutingDimension timeDim = null;
            IntervalVar[][] breaks = new IntervalVar[net.numVehicles][];
            if (net.useTime) {
                timeDim = addTime(spec, net, manager, routing, costGoal, breaks);
            }

            // ── Distance dimension: per-vehicle max distance, and pickup/delivery order ──
            boolean useMaxDistance = vehicles.stream().anyMatch(v -> v.maxDistanceMeters() > 0);
            if (useMaxDistance || !spec.pairs().isEmpty()) {
                long unbounded = Long.MAX_VALUE / 4;
                long[] perVehicleMaxDist = vehicles.stream()
                        .mapToLong(v -> v.maxDistanceMeters() > 0 ? v.maxDistanceMeters() : unbounded)
                        .toArray();
                routing.addDimensionWithVehicleCapacity(metersCb, 0, perVehicleMaxDist, true, DISTANCE_DIM);
            }

            // ── Optional stops (disjunctions with dropping penalty); pairs drop together ──
            long dropBoost = dropBoost(spec, net, vehicleBoost);
            Set<String> pairedStopIds = new HashSet<>();
            for (PickupDeliveryDef pair : spec.pairs()) {
                pairedStopIds.add(pair.pickupId());
                pairedStopIds.add(pair.deliveryId());
            }
            for (int i = 0; i < stops.size(); i++) {
                if (pairedStopIds.contains(stops.get(i).id())) continue;
                long penalty = dropPenaltyOf(spec, stops.get(i), dropBoost);
                if (penalty > 0) routing.addDisjunction(new long[]{manager.nodeToIndex(net.numDepots + i)}, penalty);
            }

            addPairs(spec, net, manager, routing, dropBoost);
            addSkills(spec, net, manager, routing);
            return new Built(timeDim, breaks);
        }

        /** Meters for every vehicle, or each vehicle's cost per meter under the total-cost goal. */
        private static void setArcCosts(RoutingSpec spec, Network net, RoutingIndexManager manager,
                                        RoutingModel routing, int metersCb, boolean costGoal) {
            List<VehicleDef> vehicles = spec.vehicles();
            boolean perVehicle = costGoal && vehicles.stream().anyMatch(v -> v.costPerMeter() != 1);
            if (!perVehicle) {
                routing.setArcCostEvaluatorOfAllVehicles(metersCb);
                return;
            }
            Map<Long, Integer> callbackOf = new HashMap<>();
            long[][] dist = net.dist;
            for (int v = 0; v < net.numVehicles; v++) {
                long coef = vehicles.get(v).costPerMeter();
                Validate.multiply(net.maxArc, coef, "Vehicle '" + vehicles.get(v).id() + "' arc cost");
                int cb = callbackOf.computeIfAbsent(coef, c -> routing.registerTransitCallback(
                        (fromIdx, toIdx) -> dist[manager.indexToNode(fromIdx)][manager.indexToNode(toIdx)] * c));
                routing.setArcCostEvaluatorOfVehicle(cb, v);
            }
        }

        /**
         * Under the vehicle-count goal every vehicle carries a cost larger than any set of
         * tours, so using one fewer vehicle always pays off; {@code 0} for other goals.
         */
        private static long vehiclesUsedBoost(RoutingSpec spec, Network net) {
            if (!(spec.goal() instanceof RoutingGoal.MinimizeVehiclesUsed)) return 0;
            return Validate.add(Validate.multiply(net.numNodes, net.maxArc, "Vehicle-count cost"), 1,
                    "Vehicle-count cost");
        }

        /**
         * Under the vehicle-count goal, added to every positive drop penalty so that dropping a
         * stop costs more than opening every vehicle and driving every arc: stops are then
         * dropped only when they cannot be served, and user penalties still rank the drops.
         */
        private static long dropBoost(RoutingSpec spec, Network net, long vehicleBoost) {
            if (vehicleBoost == 0) return 0;
            String what = "Vehicle-count drop penalty";
            long allVehicles = Validate.multiply(net.numVehicles + 1L, vehicleBoost, what);
            long allArcs = Validate.multiply(net.numNodes + (long) net.numVehicles, net.maxArc, what);
            return Validate.add(allVehicles, allArcs, what);
        }

        /** The stop's own drop penalty when set, else the model-wide one, plus the boost; {@code 0} means mandatory. */
        private static long dropPenaltyOf(RoutingSpec spec, StopDef stop, long dropBoost) {
            long base = stop.dropPenalty() > 0 ? stop.dropPenalty() : Math.max(0, spec.droppingPenalty());
            return base > 0 ? Validate.add(base, dropBoost, "Drop penalty") : 0;
        }

        /** Time dimension with hard and soft windows, vehicle windows, duration caps and costs, and breaks. */
        private static RoutingDimension addTime(RoutingSpec spec, Network net, RoutingIndexManager manager,
                                                RoutingModel routing, boolean costGoal, IntervalVar[][] breaks) {
            List<StopDef> stops = spec.stops();
            List<VehicleDef> vehicles = spec.vehicles();
            long[][] timeMatrix = net.timeMatrix(spec);
            int numDepots = net.numDepots;
            int timeCb = routing.registerTransitCallback((fromIdx, toIdx) -> {
                int from = manager.indexToNode(fromIdx);
                int to   = manager.indexToNode(toIdx);
                long service = (from >= numDepots) ? stops.get(from - numDepots).serviceTimeMin() : 0;
                return timeMatrix[from][to] + service;
            });
            // Large slack lets vehicles wait at the depot until a time window opens, and makes
            // room for breaks during a drive.
            routing.addDimension(timeCb, DAY_MINUTES, DAY_MINUTES, false, TIME_DIM);
            RoutingDimension timeDim = routing.getMutableDimension(TIME_DIM);
            for (int i = 0; i < stops.size(); i++) {
                StopDef stop = stops.get(i);
                long nodeIdx = manager.nodeToIndex(numDepots + i);
                if (stop.hasTimeWindow()) timeDim.cumulVar(nodeIdx).setRange(stop.openMin(), stop.closeMin());
                if (stop.hasSoftWindow()) {
                    timeDim.setCumulVarSoftUpperBound(nodeIdx, stop.lateAfterMin(), stop.latePenaltyPerMin());
                }
            }
            long[] visitTransits = null;
            for (int v = 0; v < net.numVehicles; v++) {
                VehicleDef vd = vehicles.get(v);
                IntVar startCumul = timeDim.cumulVar(routing.start(v));
                IntVar endCumul = timeDim.cumulVar(routing.end(v));
                if (vd.availableFromMin() > 0) startCumul.setMin(vd.availableFromMin());
                if (vd.availableUntilMin() < DAY_MINUTES) endCumul.setMax(vd.availableUntilMin());
                if (vd.maxRouteDurationMin() > 0) timeDim.setSpanUpperBoundForVehicle(vd.maxRouteDurationMin(), v);
                if (costGoal && vd.costPerMinute() > 0) timeDim.setSpanCostCoefficientForVehicle(vd.costPerMinute(), v);
                // Tighten start/end cumuls so the reported times are the earliest consistent ones.
                routing.addVariableMinimizedByFinalizer(startCumul);
                routing.addVariableMinimizedByFinalizer(endCumul);

                if (vd.breaks().isEmpty()) continue;
                if (visitTransits == null) visitTransits = visitTransits(stops, net, manager, routing);
                IntervalVar[] vehicleBreaks = new IntervalVar[vd.breaks().size()];
                for (int b = 0; b < vehicleBreaks.length; b++) {
                    BreakDef def = vd.breaks().get(b);
                    vehicleBreaks[b] = routing.solver().makeFixedDurationIntervalVar(
                            def.earliestStartMin(), def.latestStartMin(), def.durationMin(), false,
                            "break_" + vd.id() + "_" + b);
                    routing.addIntervalToAssignment(vehicleBreaks[b]);
                }
                timeDim.setBreakIntervalsOfVehicle(vehicleBreaks, v, visitTransits);
                breaks[v] = vehicleBreaks;
            }
            return timeDim;
        }

        /** Service time of each routing index: breaks may not interrupt a service. */
        private static long[] visitTransits(List<StopDef> stops, Network net, RoutingIndexManager manager,
                                            RoutingModel routing) {
            long[] transits = new long[(int) routing.size()];
            for (int idx = 0; idx < transits.length; idx++) {
                int node = manager.indexToNode(idx);
                transits[idx] = node >= net.numDepots ? stops.get(node - net.numDepots).serviceTimeMin() : 0;
            }
            return transits;
        }

        private static void addPairs(RoutingSpec spec, Network net, RoutingIndexManager manager,
                                     RoutingModel routing, long dropBoost) {
            if (spec.pairs().isEmpty()) return;
            List<StopDef> stops = spec.stops();
            Solver cp = routing.solver();
            RoutingDimension distDim = routing.getMutableDimension(DISTANCE_DIM);
            for (PickupDeliveryDef pair : spec.pairs()) {
                int pi = net.stopIndexOf.get(pair.pickupId());
                int di = net.stopIndexOf.get(pair.deliveryId());
                long p = manager.nodeToIndex(net.numDepots + pi);
                long d = manager.nodeToIndex(net.numDepots + di);
                routing.addPickupAndDelivery(p, d);
                cp.addConstraint(cp.makeEquality(routing.vehicleVar(p), routing.vehicleVar(d)));
                cp.addConstraint(cp.makeLessOrEqual(distDim.cumulVar(p), distDim.cumulVar(d)));
                long pairPenalty = Math.max(dropPenaltyOf(spec, stops.get(pi), dropBoost),
                        dropPenaltyOf(spec, stops.get(di), dropBoost));
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

        private static void addSkills(RoutingSpec spec, Network net, RoutingIndexManager manager, RoutingModel routing) {
            List<StopDef> stops = spec.stops();
            List<VehicleDef> vehicles = spec.vehicles();
            for (int i = 0; i < stops.size(); i++) {
                StopDef stop = stops.get(i);
                if (stop.requiredSkills().isEmpty()) continue;
                boolean[] qualified = new boolean[net.numVehicles];
                boolean any = false;
                for (int v = 0; v < net.numVehicles; v++) {
                    qualified[v] = vehicles.get(v).skills().containsAll(stop.requiredSkills());
                    any |= qualified[v];
                }
                if (!any) {
                    throw new ModelException("Stop '" + stop.id() + "' requires skills " + stop.requiredSkills()
                            + " but no vehicle has them.");
                }
                // Prune the node's vehicle variable instead of RoutingModel.setAllowedVehiclesForIndex,
                // whose only binding takes an absl::Span that the Java wrapper cannot construct. Removing
                // the unqualified vehicles is equivalent and, unlike a member constraint over the allowed
                // set, leaves -1 (the "not served" value) in the domain, so the stop can still be dropped.
                IntVar vehicleVar = routing.vehicleVar(manager.nodeToIndex(net.numDepots + i));
                for (int v = 0; v < net.numVehicles; v++) {
                    if (!qualified[v]) vehicleVar.removeValue(v);
                }
            }
        }
    }

    // ── Warm start ──────────────────────────────────────────────────────────

    /** Translates a previous plan into routing indices, skipping ids this model no longer has. */
    private static final class WarmStart {
        static long[][] routes(RoutingPlan previous, RoutingSpec spec, Network net, RoutingIndexManager manager) {
            Map<String, Integer> vehicleIndexOf = new HashMap<>();
            for (int v = 0; v < net.numVehicles; v++) vehicleIndexOf.put(spec.vehicles().get(v).id(), v);
            long[][] routes = new long[net.numVehicles][0];
            Set<String> seen = new HashSet<>();
            for (Route r : previous.routes()) {
                Integer v = vehicleIndexOf.get(r.vehicle());
                if (v == null) continue;
                List<Long> indices = new ArrayList<>();
                for (String stopId : r.stops()) {
                    Integer i = net.stopIndexOf.get(stopId);
                    if (i != null && seen.add(stopId)) indices.add(manager.nodeToIndex(net.numDepots + i));
                }
                routes[v] = indices.stream().mapToLong(Long::longValue).toArray();
            }
            return routes;
        }
    }

    // ── Extraction ──────────────────────────────────────────────────────────

    /** Solved values, read from a final {@link Assignment} or from live variables during the search. */
    private interface Values {
        long next(long index);
        long cumulMin(IntVar cumul);
        long breakStart(IntervalVar interval);
    }

    /** Builds a pure-Java {@link RoutingPlan} from solved values. */
    private static final class PlanReader {
        private final RoutingSpec spec;
        private final Network net;
        private final RoutingIndexManager manager;
        private final RoutingModel routing;
        private final Built built;

        PlanReader(RoutingSpec spec, Network net, RoutingIndexManager manager, RoutingModel routing, Built built) {
            this.spec = spec;
            this.net = net;
            this.manager = manager;
            this.routing = routing;
            this.built = built;
        }

        Values of(Assignment solution) {
            return new Values() {
                @Override public long next(long index)               { return solution.value(routing.nextVar(index)); }
                @Override public long cumulMin(IntVar cumul)          { return solution.min(cumul); }
                @Override public long breakStart(IntervalVar interval) { return solution.startValue(interval); }
            };
        }

        /** Live variables, valid inside an at-solution callback where every next variable is bound. */
        Values live() {
            return new Values() {
                @Override public long next(long index)               { return routing.nextVar(index).value(); }
                @Override public long cumulMin(IntVar cumul)          { return cumul.min(); }
                @Override public long breakStart(IntervalVar interval) { return interval.startMin(); }
            };
        }

        RoutingPlan read(Values values, SolveStatus status, java.time.Duration runtime) {
            List<StopDef> stops = spec.stops();
            List<VehicleDef> vehicles = spec.vehicles();
            RoutingDimension timeDim = built.timeDim();
            String defaultDim = RoutingBuilder.DEFAULT_DIMENSION;

            List<Route> routes = new ArrayList<>();
            Set<String> visitedStops = new HashSet<>();
            long totalDistance = 0;
            long totalCost = 0;

            for (int v = 0; v < net.numVehicles; v++) {
                VehicleDef vd = vehicles.get(v);
                List<String> routeStops = new ArrayList<>();
                List<Visit> visits = new ArrayList<>();
                long routeDistance = 0;
                long lateness = 0;
                Map<String, Long> routeLoads = new TreeMap<>();
                Map<String, Long> onBoard = new TreeMap<>();
                for (String d : net.dimensions) {
                    routeLoads.put(d, 0L);
                    onBoard.put(d, 0L);
                }
                long index = routing.start(v);
                Long startMin = timeDim == null ? null : values.cumulMin(timeDim.cumulVar(index));
                while (!routing.isEnd(index)) {
                    int node = manager.indexToNode(index);
                    long next = values.next(index);
                    routeDistance += net.dist[node][manager.indexToNode(next)];
                    if (node >= net.numDepots) {
                        StopDef stop = stops.get(node - net.numDepots);
                        routeStops.add(stop.id());
                        visitedStops.add(stop.id());
                        boolean delivery = net.deliveryStopIds.contains(stop.id());
                        for (String d : net.dimensions) {
                            onBoard.merge(d, net.signedDemands.get(d)[node], Long::sum);
                            if (!delivery) routeLoads.merge(d, stop.demand(d), Long::sum);
                        }
                        LocalTime arrival = null, departure = null;
                        long late = 0;
                        if (timeDim != null) {
                            long arrivalMin = values.cumulMin(timeDim.cumulVar(index));
                            arrival = minutesToTime(arrivalMin);
                            departure = minutesToTime(arrivalMin + stop.serviceTimeMin());
                            if (stop.hasSoftWindow()) {
                                late = Math.max(0, arrivalMin - stop.lateAfterMin());
                                lateness += late * stop.latePenaltyPerMin();
                            }
                        }
                        visits.add(new Visit(stop.id(), arrival, departure, onBoard.getOrDefault(defaultDim, 0L),
                                onBoard, late));
                    }
                    index = next;
                }
                if (routeStops.isEmpty()) continue;
                Long endMin = timeDim == null ? null : values.cumulMin(timeDim.cumulVar(index));
                long span = (startMin == null) ? 0 : endMin - startMin;
                long variableCost = routeDistance * vd.costPerMeter() + span * vd.costPerMinute();
                List<DriverBreak> breaks = new ArrayList<>();
                if (built.breaks()[v] != null) {
                    for (int b = 0; b < built.breaks()[v].length; b++) {
                        long start = values.breakStart(built.breaks()[v][b]);
                        breaks.add(new DriverBreak(minutesToTime(start),
                                minutesToTime(start + vd.breaks().get(b).durationMin())));
                    }
                }
                totalDistance += routeDistance;
                totalCost += variableCost + vd.fixedCost() + lateness;
                routes.add(new Route(vd.id(), net.startDepotIdOf[v], routeStops, visits, routeDistance,
                        routeLoads.getOrDefault(defaultDim, 0L), routeLoads, vd.fixedCost(), variableCost,
                        lateness, breaks,
                        startMin == null ? null : minutesToTime(startMin),
                        endMin == null ? null : minutesToTime(endMin)));
            }

            List<String> unassigned = stops.stream()
                    .map(StopDef::id)
                    .filter(id -> !visitedStops.contains(id))
                    .toList();

            double objective = switch (spec.goal()) {
                case RoutingGoal.MinimizeTotalDistance g -> totalDistance;
                case RoutingGoal.MinimizeTotalCost g     -> totalCost;
                case RoutingGoal.MinimizeVehiclesUsed g  -> routes.size();
            };
            return new RoutingPlan(status, OptionalDouble.of(objective), runtime, routes, unassigned);
        }
    }

    // ── Search parameters ───────────────────────────────────────────────────

    private static final class SearchParams {
        static RoutingSearchParameters of(Strategy firstSolution, Metaheuristic metaheuristic,
                                          long timeLimitMillis, boolean verbose) {
            RoutingSearchParameters.Builder builder = main.defaultRoutingSearchParameters()
                    .toBuilder()
                    .setFirstSolutionStrategy(strategy(firstSolution))
                    .setLocalSearchMetaheuristic(metaheuristic(metaheuristic))
                    .setTimeLimit(Duration.newBuilder()
                            .setSeconds(timeLimitMillis / 1000)
                            .setNanos((int) ((timeLimitMillis % 1000) * 1_000_000))
                            .build());
            if (verbose) {
                builder.setLogSearch(true);
            }
            return builder.build();
        }

        private static FirstSolutionStrategy.Value strategy(Strategy s) {
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

        private static LocalSearchMetaheuristic.Value metaheuristic(Metaheuristic m) {
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
    }
}

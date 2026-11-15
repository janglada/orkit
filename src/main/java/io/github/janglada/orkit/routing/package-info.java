/**
 * Vehicle routing solver.
 *
 * <p>Offers a fluent DSL for modeling vehicle routing problems (VRP): one or more
 * depots, vehicles with capacities, stops with demands and time windows, pluggable
 * distance sources, first-solution heuristics and local-search metaheuristics.
 *
 * <p><b>Multiple depots.</b> {@code depot(id, lat, lon)} is accumulative — call it
 * once per depot; the first registered depot is the default. Assign a vehicle to a
 * depot with {@link io.github.janglada.orkit.routing.VehicleConfig#startsAt(String)} /
 * {@link io.github.janglada.orkit.routing.VehicleConfig#endsAt(String)} (a vehicle may start and end at
 * different depots). A vehicle with no assignment uses the default depot for both
 * ends. Depot ids are resolved at solve time; an unknown id raises
 * {@link io.github.janglada.orkit.ModelException}. Each solved {@link io.github.janglada.orkit.routing.Route} reports its
 * start depot via {@link io.github.janglada.orkit.routing.Route#depotId()}.
 *
 * <p><b>Limitation: no time-dependent travel.</b> The native OR-Tools
 * {@code RoutingModel} transit callback is a pure function of
 * {@code (fromNode, toNode)} (optionally vehicle) and cannot read the
 * arrival-time variable, so travel time cannot vary by time of day.
 * {@code travelSpeedKmh} is therefore a single global scalar, not a
 * time-banded speed profile. Static time windows and per-vehicle depots
 * are fully supported; true time-dependent travel would require a CP-SAT
 * routing solver and is not yet implemented.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#vehicleRouting()}.
 */
package io.github.janglada.orkit.routing;

/**
 * Vehicle routing solver.
 *
 * <p>Offers a fluent DSL for modelling vehicle routing problems (VRP): one or more
 * depots, vehicles with capacities, stops with demands and time windows, pluggable
 * distance sources, first-solution heuristics and local-search metaheuristics.
 *
 * <p><b>Multiple depots.</b> {@code depot(id, lat, lon)} is accumulative — call it
 * once per depot; the first registered depot is the default. Assign a vehicle to a
 * depot with {@link opt.routing.VehicleConfig#startsAt(String)} /
 * {@link opt.routing.VehicleConfig#endsAt(String)} (a vehicle may start and end at
 * different depots). A vehicle with no assignment uses the default depot for both
 * ends. Depot ids are resolved at solve time; an unknown id raises
 * {@link opt.ModelException}. Each solved {@link opt.routing.Route} reports its
 * start depot via {@link opt.routing.Route#depotId()}.
 *
 * <p>Entry point: {@link opt.Optimize#vehicleRouting()}.
 */
package opt.routing;

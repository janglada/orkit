package io.github.janglada.orkit.routing;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * The route assigned to a single vehicle in the solved plan.
 *
 * @param vehicle         id of the vehicle serving this route
 * @param depotId         id of the depot this vehicle departs from (and returns to, unless
 *                        the vehicle was assigned distinct start/end depots, in which case
 *                        this is the start depot)
 * @param stops           ids of the stops visited, in travel order (depots excluded)
 * @param visits          one {@link Visit} per entry of {@code stops}, in the same order, with
 *                        arrival/departure times and the running load
 * @param distanceMeters  total distance of this route, in meters
 * @param load            total declared demand picked up on this route in the default
 *                        {@value RoutingBuilder#DEFAULT_DIMENSION} dimension (pair deliveries excluded)
 * @param loads           the same total per capacity dimension with any demand
 * @param fixedCost       the vehicle's fixed cost, charged because the route is non-empty
 * @param variableCost    {@code distanceMeters * costPerMeter + duration minutes * costPerMinute}
 * @param latenessPenalty cost of the minutes this route serves stops after their soft deadlines
 * @param breaks          the vehicle's driver breaks, in declaration order
 * @param startTime       departure time from the start depot; {@code null} without a time dimension
 * @param endTime         arrival time at the end depot; {@code null} without a time dimension
 */
public record Route(
        String vehicle,
        String depotId,
        List<String> stops,
        List<Visit> visits,
        long distanceMeters,
        long load,
        Map<String, Long> loads,
        long fixedCost,
        long variableCost,
        long latenessPenalty,
        List<DriverBreak> breaks,
        LocalTime startTime,
        LocalTime endTime
) {
    /** Copies the collections. */
    public Route {
        stops = List.copyOf(stops);
        visits = List.copyOf(visits);
        loads = Map.copyOf(loads);
        breaks = List.copyOf(breaks);
    }

    /**
     * Whether times are reported on this route. The time dimension is modeled only when some
     * time feature is present: a stop window, soft deadline or service time, a vehicle
     * availability window, route-duration cap, break or per-minute cost, or a travel-time matrix.
     */
    public boolean hasTimes() {
        return startTime != null;
    }
}

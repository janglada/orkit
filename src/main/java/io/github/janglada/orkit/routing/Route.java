package io.github.janglada.orkit.routing;

import java.time.LocalTime;
import java.util.List;

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
 * @param distanceMeters  total arc cost of this route, in meters
 * @param load            total declared demand picked up on this route (pair deliveries excluded)
 * @param fixedCost       the vehicle's fixed cost, charged because the route is non-empty
 * @param startTime       departure time from the start depot; {@code null} without a time dimension
 * @param endTime         arrival time at the end depot; {@code null} without a time dimension
 */
public record Route(
        String vehicle,
        String depotId,
        List<String> stops,
        List<Visit> visits,
        long distanceMeters,
        int load,
        long fixedCost,
        LocalTime startTime,
        LocalTime endTime
) {
    /**
     * Whether times are reported on this route. The time dimension is modeled only when some
     * time feature is present: a stop window or service time, a vehicle availability window or
     * route-duration cap, or a travel-time matrix.
     */
    public boolean hasTimes() {
        return startTime != null;
    }
}

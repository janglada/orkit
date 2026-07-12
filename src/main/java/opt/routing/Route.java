package opt.routing;

import java.util.List;

/**
 * The route assigned to a single vehicle in the solved plan.
 *
 * @param vehicle         id of the vehicle serving this route
 * @param depotId         id of the depot this vehicle departs from (and returns to, unless
 *                        the vehicle was assigned distinct start/end depots, in which case
 *                        this is the start depot)
 * @param stops           ids of the stops visited, in travel order (depots excluded)
 * @param distanceMeters  total arc cost of this route, in meters
 * @param load            cumulative demand served across all stops in this route
 */
public record Route(
        String vehicle,
        String depotId,
        List<String> stops,
        long distanceMeters,
        int load
) {}

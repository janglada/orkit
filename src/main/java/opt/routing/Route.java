package opt.routing;

import java.util.List;

/**
 * The route assigned to a single vehicle in the solved plan.
 *
 * @param vehicle         id of the vehicle serving this route
 * @param stops           ids of the stops visited, in travel order (depot excluded)
 * @param distanceMeters  total arc cost of this route, in meters
 * @param load            cumulative demand served across all stops in this route
 */
public record Route(
        String vehicle,
        List<String> stops,
        long distanceMeters,
        int load
) {}

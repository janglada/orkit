package opt.routing;

import java.util.List;

/** The route assigned to a single vehicle in the solved plan. */
public record Route(
        String vehicle,
        List<String> stops,
        long distanceMeters,
        int load
) {}

package opt.routing.internal;

/**
 * Immutable description of a single depot, used internally by the routing compiler.
 *
 * <p>Depots are the nodes vehicles depart from and return to. The first registered
 * depot (index 0) is the default for vehicles that do not specify one.
 *
 * @param id  depot identifier
 * @param lat depot latitude, used for haversine distances
 * @param lon depot longitude, used for haversine distances
 */
public record DepotDef(String id, double lat, double lon) {}

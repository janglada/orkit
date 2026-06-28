package opt.routing.internal;

/**
 * Immutable description of a single vehicle, used internally by the routing compiler.
 *
 * @param id                  vehicle identifier
 * @param capacity            carrying capacity in demand units; {@link Integer#MAX_VALUE} means unbounded
 * @param maxDistanceMeters   cap on total distance this vehicle may travel; {@code 0} means no cap
 */
public record VehicleDef(String id, int capacity, long maxDistanceMeters) {}

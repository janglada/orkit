package opt.routing.internal;

/**
 * Immutable description of a single vehicle, used internally by the routing compiler.
 *
 * @param id               vehicle identifier
 * @param capacity         carrying capacity in demand units; {@link Integer#MAX_VALUE} means unbounded
 * @param maxDistanceMeters cap on total distance this vehicle may travel; {@code 0} means no cap
 * @param startDepotId     id of the depot this vehicle departs from; {@code null} means use the default (first) depot
 * @param endDepotId       id of the depot this vehicle returns to; {@code null} means use the default (first) depot.
 *                         If exactly one of {@code startDepotId}/{@code endDepotId} is set, it is used for both ends.
 */
public record VehicleDef(
        String id,
        int capacity,
        long maxDistanceMeters,
        String startDepotId,
        String endDepotId
) {}

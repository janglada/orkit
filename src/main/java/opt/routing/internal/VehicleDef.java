package opt.routing.internal;

import java.util.Set;

/**
 * Immutable description of a single vehicle, used internally by the routing compiler.
 *
 * @param id                 vehicle identifier
 * @param capacity           carrying capacity in demand units; {@link Integer#MAX_VALUE} means unbounded
 * @param maxDistanceMeters  cap on total distance this vehicle may travel; {@code 0} means no cap
 * @param startDepotId       id of the depot this vehicle departs from; {@code null} means the default (first) depot
 * @param endDepotId         id of the depot this vehicle returns to; {@code null} means the default (first) depot.
 *                           If exactly one of {@code startDepotId}/{@code endDepotId} is set, it is used for both ends.
 * @param availableFromMin   earliest departure from the start depot, minutes from midnight (0 = no bound)
 * @param availableUntilMin  latest return to the end depot, minutes from midnight (1440 = no bound)
 * @param maxRouteDurationMin cap on end minus start time in minutes; {@code 0} means no cap
 * @param fixedCost          cost charged once when the vehicle serves at least one stop
 * @param skills             capabilities this vehicle offers (matched against {@link StopDef#requiredSkills()})
 */
public record VehicleDef(
        String id,
        int capacity,
        long maxDistanceMeters,
        String startDepotId,
        String endDepotId,
        int availableFromMin,
        int availableUntilMin,
        long maxRouteDurationMin,
        long fixedCost,
        Set<String> skills
) {
    /** Whether any time-related setting requires the time dimension to be modeled. */
    public boolean hasTimeSettings() {
        return availableFromMin > 0 || availableUntilMin < 24 * 60 || maxRouteDurationMin > 0;
    }
}

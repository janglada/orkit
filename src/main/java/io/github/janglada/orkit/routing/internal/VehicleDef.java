package io.github.janglada.orkit.routing.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable description of a single vehicle, used internally by the routing compiler.
 *
 * @param id                 vehicle identifier
 * @param capacities         carrying capacity per dimension; a dimension absent here is unbounded
 * @param maxDistanceMeters  cap on total distance this vehicle may travel; {@code 0} means no cap
 * @param startDepotId       id of the depot this vehicle departs from; {@code null} means the default (first) depot
 * @param endDepotId         id of the depot this vehicle returns to; {@code null} means the default (first) depot.
 *                           If exactly one of {@code startDepotId}/{@code endDepotId} is set, it is used for both ends.
 * @param availableFromMin   earliest departure from the start depot, minutes from midnight (0 = no bound)
 * @param availableUntilMin  latest return to the end depot, minutes from midnight (1440 = no bound)
 * @param maxRouteDurationMin cap on end minus start time in minutes; {@code 0} means no cap
 * @param fixedCost          cost charged once when the vehicle serves at least one stop
 * @param skills             capabilities this vehicle offers (matched against {@link StopDef#requiredSkills()})
 * @param breaks             driver breaks the route must make room for
 * @param costPerMeter       cost per meter driven, used by the total-cost goal
 * @param costPerMinute      cost per minute of route duration, used by the total-cost goal
 */
public record VehicleDef(
        String id,
        Map<String, Long> capacities,
        long maxDistanceMeters,
        String startDepotId,
        String endDepotId,
        int availableFromMin,
        int availableUntilMin,
        long maxRouteDurationMin,
        long fixedCost,
        Set<String> skills,
        List<BreakDef> breaks,
        long costPerMeter,
        long costPerMinute
) {
    /** Copies the collections. */
    public VehicleDef {
        capacities = Map.copyOf(capacities);
        skills = Set.copyOf(skills);
        breaks = List.copyOf(breaks);
    }

    /** Whether any time-related setting requires the time dimension to be modeled. */
    public boolean hasTimeSettings() {
        return availableFromMin > 0 || availableUntilMin < 24 * 60 || maxRouteDurationMin > 0
                || !breaks.isEmpty() || costPerMinute > 0;
    }
}

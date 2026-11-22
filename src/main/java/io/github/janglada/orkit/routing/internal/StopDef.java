package io.github.janglada.orkit.routing.internal;

import java.util.Map;
import java.util.Set;

/**
 * Immutable description of a single stop, used internally by the routing compiler.
 *
 * @param id                stop identifier
 * @param lat               latitude, used for haversine distances
 * @param lon               longitude, used for haversine distances
 * @param demands           demand served at this stop per capacity dimension (absent means 0)
 * @param serviceTimeMin    dwell time in minutes the vehicle spends at this stop
 * @param openMin           earliest time-of-day (minutes from midnight) the stop may be served
 * @param closeMin          latest time-of-day (minutes from midnight) the stop may be served
 * @param requiredSkills    skills a vehicle must have to serve this stop; empty means any vehicle
 * @param dropPenalty       this stop's own drop penalty; {@code 0} defers to the model-wide one
 * @param lateAfterMin      soft latest service start, minutes from midnight; {@code -1} means none
 * @param latePenaltyPerMin cost per minute of service start after {@code lateAfterMin}
 */
public record StopDef(
        String id,
        double lat,
        double lon,
        Map<String, Long> demands,
        int serviceTimeMin,
        int openMin,
        int closeMin,
        Set<String> requiredSkills,
        long dropPenalty,
        int lateAfterMin,
        long latePenaltyPerMin
) {
    /** Copies the demand map and skill set. */
    public StopDef {
        demands = Map.copyOf(demands);
        requiredSkills = Set.copyOf(requiredSkills);
    }

    /** Demand in the given dimension; {@code 0} when the stop declares none. */
    public long demand(String dimension) {
        return demands.getOrDefault(dimension, 0L);
    }

    /** Returns {@code true} when this stop has a non-default time window that the solver must enforce. */
    public boolean hasTimeWindow() {
        return openMin > 0 || closeMin < 24 * 60;
    }

    /** Returns {@code true} when this stop has a soft latest service start. */
    public boolean hasSoftWindow() {
        return lateAfterMin >= 0;
    }
}

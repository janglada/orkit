package opt.routing.internal;

import java.util.Set;

/**
 * Immutable description of a single stop, used internally by the routing compiler.
 *
 * @param id              stop identifier
 * @param lat             latitude, used for haversine distances
 * @param lon             longitude, used for haversine distances
 * @param demand          demand served at this stop, in vehicle-capacity units
 * @param serviceTimeMin  dwell time in minutes the vehicle spends at this stop
 * @param openMin         earliest time-of-day (minutes from midnight) the stop may be served
 * @param closeMin        latest time-of-day (minutes from midnight) the stop may be served
 * @param requiredSkills  skills a vehicle must have to serve this stop; empty means any vehicle
 * @param dropPenalty     this stop's own drop penalty; {@code 0} defers to the model-wide one
 */
public record StopDef(
        String id,
        double lat,
        double lon,
        int demand,
        int serviceTimeMin,
        int openMin,
        int closeMin,
        Set<String> requiredSkills,
        long dropPenalty
) {
    /** Returns {@code true} when this stop has a non-default time window that the solver must enforce. */
    public boolean hasTimeWindow() {
        return openMin > 0 || closeMin < 24 * 60;
    }
}

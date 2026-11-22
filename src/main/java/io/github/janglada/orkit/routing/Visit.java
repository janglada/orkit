package io.github.janglada.orkit.routing;

import java.time.LocalTime;
import java.util.Map;

/**
 * One stop visit on a solved {@link Route}.
 *
 * @param stop        id of the visited stop
 * @param arrival     time service starts at the stop (after any waiting for the window to open);
 *                    {@code null} when the model has no time dimension
 * @param departure   {@code arrival + serviceTime}; {@code null} when the model has no time dimension
 * @param loadAfter   load in the default {@value RoutingBuilder#DEFAULT_DIMENSION} dimension after
 *                    leaving the stop (pickups add, deliveries of a pair subtract)
 * @param loadsAfter  load after leaving the stop, per capacity dimension with any demand
 * @param minutesLate minutes the service started after the stop's
 *                    {@link StopConfig#lateAfter(LocalTime, long) soft deadline}; {@code 0} otherwise
 */
public record Visit(String stop, LocalTime arrival, LocalTime departure, long loadAfter,
                    Map<String, Long> loadsAfter, long minutesLate) {

    /** Copies the per-dimension loads. */
    public Visit {
        loadsAfter = Map.copyOf(loadsAfter);
    }
}

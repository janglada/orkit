package io.github.janglada.orkit.routing;

import java.time.LocalTime;

/**
 * One stop visit on a solved {@link Route}.
 *
 * @param stop       id of the visited stop
 * @param arrival    time service starts at the stop (after any waiting for the window to open);
 *                   {@code null} when the model has no time dimension
 * @param departure  {@code arrival + serviceTime}; {@code null} when the model has no time dimension
 * @param loadAfter  vehicle load after leaving the stop (pickups add, deliveries of a pair subtract)
 */
public record Visit(String stop, LocalTime arrival, LocalTime departure, int loadAfter) {}

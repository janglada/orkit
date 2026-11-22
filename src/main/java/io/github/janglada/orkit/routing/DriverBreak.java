package io.github.janglada.orkit.routing;

import java.time.LocalTime;

/**
 * A driver break placed on a solved {@link Route}. A break may fall before the route starts or
 * after it ends, when the window allows; otherwise it interrupts a drive between two stops.
 *
 * @param start break start
 * @param end   break end
 */
public record DriverBreak(LocalTime start, LocalTime end) {}

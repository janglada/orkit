package io.github.janglada.orkit.routing.internal;

/**
 * A driver break: {@code durationMin} minutes starting between {@code earliestStartMin} and
 * {@code latestStartMin}, all in minutes from midnight.
 *
 * @param earliestStartMin earliest break start
 * @param latestStartMin   latest break start
 * @param durationMin      break length
 */
public record BreakDef(int earliestStartMin, int latestStartMin, int durationMin) {}

/**
 * Employee shift-scheduling solver.
 *
 * <p>Defines a fluent DSL for modeling weekly or multi-week rosters: employees, shifts
 * (day/evening/night with start/end times), hard constraints ({@link io.github.janglada.orkit.scheduling.Rules})
 * and soft objectives ({@link io.github.janglada.orkit.scheduling.Goals}).
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#scheduling()}.
 */
package io.github.janglada.orkit.scheduling;
/**
 * Employee shift-scheduling solver.
 *
 * <p>Defines a fluent DSL for modeling weekly or multi-week rosters: employees, shifts
 * (day/evening/night with start/end times), hard constraints ({@link io.github.janglada.orkit.scheduling.Rules})
 * and soft objectives ({@link io.github.janglada.orkit.scheduling.Goals}).
 *
 * <p>Shifts may require several skills and a role mix; employees may have weekly maximum,
 * minimum and contracted hours. The roster reports each assignment's role and cost, the total
 * labor cost in currency, and each employee's hours.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#scheduling()}.
 */
package io.github.janglada.orkit.scheduling;
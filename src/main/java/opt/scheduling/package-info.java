/**
 * Employee shift-scheduling solver.
 *
 * <p>Defines a fluent DSL for modelling weekly or multi-week rosters: employees, shifts
 * (day/evening/night with start/end times), hard constraints ({@link opt.scheduling.Rules})
 * and soft objectives ({@link opt.scheduling.Goals}).
 *
 * <p>Entry point: {@link opt.Optimize#scheduling()}.
 */
package opt.scheduling;
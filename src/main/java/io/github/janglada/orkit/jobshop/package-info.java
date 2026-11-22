/**
 * Job-shop scheduling solver.
 *
 * <p>Provides a fluent DSL for modeling job-shop and flexible job-shop problems: machines,
 * jobs composed of sequential operations, machine assignments, durations, sequence-dependent
 * setup times, and per-operation time windows (release dates, hard deadlines, soft due dates).
 * Jobs may depend on one another through precedences, machines may run several operations at
 * once, and a calendar origin lets inputs and results use {@code LocalDateTime} and
 * {@code Duration} instead of abstract time units.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#jobShop()}.
 */
package io.github.janglada.orkit.jobshop;
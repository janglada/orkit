/**
 * Job-shop scheduling solver.
 *
 * <p>Provides a fluent DSL for modeling job-shop and flexible job-shop problems: machines,
 * jobs composed of sequential operations, machine assignments, durations, sequence-dependent
 * setup times, and per-operation time windows (release dates, hard deadlines, soft due dates).
 *
 * <p>Entry point: {@link opt.Optimize#jobShop()}.
 */
package opt.jobshop;
/**
 * Resource booking / appointment scheduling solver.
 *
 * <p>Provides a fluent DSL for assigning appointments (each with a duration and one or more
 * requirements, each a set of candidate resources) to bookable resources, where each resource
 * may declare availability slots and at most one appointment may occupy a resource at a time.
 * An appointment books one resource of every requirement at the same time. The model uses one
 * start time per appointment and optional CP-SAT interval variables with a per-resource
 * {@code NoOverlap} constraint; ties are broken toward earlier starts.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#booking()}.
 */
package io.github.janglada.orkit.booking;

/**
 * Resource booking / appointment scheduling solver.
 *
 * <p>Provides a fluent DSL for assigning appointments (each with a duration and a set of
 * candidate resources) to bookable resources, where each resource may declare availability
 * slots and at most one appointment may occupy a resource at a time. The model uses
 * optional CP-SAT interval variables with a per-resource {@code NoOverlap} constraint.
 *
 * <p>Entry point: {@link opt.Optimize#booking()}.
 */
package opt.booking;

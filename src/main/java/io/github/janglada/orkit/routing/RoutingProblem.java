package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.OptimizationProblem;

/**
 * A built, immutable routing problem that yields a {@link RoutingSolver}
 * (not just a generic {@link io.github.janglada.orkit.Solver}) so that {@code firstSolution} and
 * {@code improveWith} are available without a cast.
 *
 * <p>Obtain one via {@link RoutingBuilder#build()}.
 */
public interface RoutingProblem extends OptimizationProblem<RoutingPlan> {

    /** Returns a {@link RoutingSolver} that exposes routing-specific search knobs. */
    @Override
    RoutingSolver solver();
}
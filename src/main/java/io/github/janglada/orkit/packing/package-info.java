/**
 * Container loading / 3D bin packing solver.
 *
 * <p>Provides a fluent DSL for packing boxes (each with a length, width, height, and
 * weight) into containers (each with dimensions and a maximum weight capacity), while
 * respecting 3D non-overlap placement and per-container weight limits.
 *
 * <p>Boxes may optionally opt into orientation and stacking sub-features:
 * <ul>
 *   <li><b>Rotation</b> &mdash; {@code BoxConfig.rotatable(true)} lets the solver reorient
 *       the box to any distinct axis-permutation of its dimensions (up to six; a cube has
 *       one). The chosen {@link Orientation} is reported on each
 *       {@link ContainerAssignment.BoxPlacement}.</li>
 *   <li><b>Support footprint</b> &mdash; {@code PackingBuilder.requireSupport(true)}
 *       forces every box resting on another to have its XY footprint contained within
 *       the supporting box below (no floating boxes).</li>
 *   <li><b>Stacking precedence</b> &mdash; {@code BoxConfig.above(id)} / {@code below(id)}
 *       declare an explicit vertical order between two boxes.</li>
 *   <li><b>Weight-bearing</b> &mdash; {@code BoxConfig.maxStackedWeight(w)} caps the total
 *       weight of boxes stacked on a supporting box.</li>
 * </ul>
 * All sub-features default off, so a model with no opt-ins is the classical
 * fixed-orientation, floating, no-overlap packing.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#packing()}.
 */
package io.github.janglada.orkit.packing;

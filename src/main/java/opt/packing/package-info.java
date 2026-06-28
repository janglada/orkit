/**
 * Container loading / 3D bin packing solver.
 *
 * <p>Provides a fluent DSL for packing boxes (each with a length, width, height, and
 * weight) into containers (each with dimensions and a maximum weight capacity), while
 * respecting 3D non-overlap placement and per-container weight limits.
 *
 * <p>Entry point: {@link opt.Optimize#packing()}.
 */
package opt.packing;

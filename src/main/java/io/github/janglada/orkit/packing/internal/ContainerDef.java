package io.github.janglada.orkit.packing.internal;

/**
 * Immutable description of a container.
 *
 * @param id        the container identifier
 * @param length    the interior length (X-axis extent)
 * @param width     the interior width (Y-axis extent)
 * @param height    the interior height (Z-axis extent)
 * @param maxWeight the maximum total weight this container can hold
 */
public record ContainerDef(String id, long length, long width, long height, long maxWeight) {}

package opt.packing;

import java.util.List;

/**
 * The boxes placed into a single container in a solved solution.
 *
 * @param containerId  the container identifier
 * @param length       the container's interior length (X extent)
 * @param width        the container's interior width (Y extent)
 * @param height       the container's interior height (Z extent)
 * @param maxWeight    the container's declared maximum weight capacity
 * @param boxes        the placements of boxes packed into this container (insertion order)
 * @param usedVolume   the sum of the volumes of {@code boxes}
 * @param usedWeight   the sum of the weights of {@code boxes}
 */
public record ContainerAssignment(
        String containerId,
        long length,
        long width,
        long height,
        long maxWeight,
        List<BoxPlacement> boxes,
        long usedVolume,
        long usedWeight
) {

    /**
     * The placement of a single box within a container.
     *
     * @param boxId       the box identifier
     * @param x           the box origin X coordinate (>= 0)
     * @param y           the box origin Y coordinate (>= 0)
     * @param z           the box origin Z coordinate (>= 0)
     * @param length      the oriented box length (X extent actually applied)
     * @param width       the oriented box width (Y extent actually applied)
     * @param height      the oriented box height (Z extent actually applied)
     * @param orientation the orientation chosen by the solver ({@code null} when the box is
     *                    not rotatable, in which case {@code length/width/height} equal the
     *                    declared canonical dimensions)
     */
    public record BoxPlacement(
            String boxId,
            long x, long y, long z,
            long length, long width, long height,
            Orientation orientation
    ) {}
}

package io.github.janglada.orkit.packing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One admissible axis-permutation of a box's {@code (length, width, height)} triple,
 * i.e. the concrete (X, Y, Z) extents applied to a placed box after orientation is
 * chosen by the solver.
 *
 * <p>An orientation is the result of rotating a cuboid box so that its canonical
 * {@code (length, width, height)} dimensions are reassigned to the container's
 * {@code (X, Y, Z)} axes. A non-rotatable box always uses its canonical orientation;
 * a rotatable box may take any of the distinct permutations of its dimensions (up to
 * six; a cube has exactly one). The {@link #distinctPermutations(long, long, long)}
 * helper deduplicates identical triples.
 *
 * @param length the X-axis extent of the oriented box
 * @param width  the Y-axis extent of the oriented box
 * @param height the Z-axis extent of the oriented box
 */
public record Orientation(long length, long width, long height) {

    /**
     * Returns the distinct axis-permutations of the given {@code (length, width, height)}
     * triple, in a stable order, deduplicating identical triples (so a cube yields a
     * single orientation). The returned list always has between 1 and 6 elements.
     *
     * @param length the canonical length
     * @param width  the canonical width
     * @param height the canonical height
     * @return the distinct orientations, never empty
     */
    public static List<Orientation> distinctPermutations(long length, long width, long height) {
        long[] dims = {length, width, height};
        Set<Orientation> seen = new LinkedHashSet<>();
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                if (b == a) continue;
                int c = 3 - a - b;
                seen.add(new Orientation(dims[a], dims[b], dims[c]));
            }
        }
        return new ArrayList<>(seen);
    }
}

package opt.packing.internal;

import opt.packing.Orientation;

import java.util.List;

/**
 * Immutable description of a box.
 *
 * @param id                the box identifier
 * @param length            the canonical box length (X-axis extent when not rotated)
 * @param width             the canonical box width (Y-axis extent when not rotated)
 * @param height            the canonical box height (Z-axis extent when not rotated)
 * @param weight            the weight this box contributes toward its container's capacity
 * @param rotatable         whether the box may be reoriented (any distinct axis-permutation)
 * @param maxStackedWeight  the maximum total weight of boxes resting directly on this one;
 *                           {@code Long.MAX_VALUE} = unbounded
 * @param aboveIds          ids of boxes that must sit <em>below</em> this one (this box is above them)
 * @param belowIds          ids of boxes that must sit <em>above</em> this one (this box is below them)
 */
public record BoxDef(
        String id,
        long length, long width, long height, long weight,
        boolean rotatable,
        long maxStackedWeight,
        List<String> aboveIds,
        List<String> belowIds
) {

    /** The volume occupied by this box (length * width * height). */
    public long volume() {
        return length * width * height;
    }

    /**
     * Whether this box participates in any of the orientation / stacking sub-features,
     * i.e. the solver must take the extended modeling path for at least this box.
     */
    public boolean hasStackingOrRotationFeatures() {
        return rotatable
                || maxStackedWeight != Long.MAX_VALUE
                || !aboveIds.isEmpty()
                || !belowIds.isEmpty();
    }

    /**
     * The admissible orientations of this box: the distinct axis-permutations of
     * {@code (length, width, height)} when {@link #rotatable()} is true, otherwise the
     * single canonical orientation.
     */
    public List<Orientation> orientations() {
        if (!rotatable) {
            return List.of(new Orientation(length, width, height));
        }
        return Orientation.distinctPermutations(length, width, height);
    }
}

package opt.packing.internal;

/**
 * Immutable description of a box.
 *
 * @param id     the box identifier
 * @param length the box length (X-axis extent)
 * @param width  the box width (Y-axis extent)
 * @param height the box height (Z-axis extent)
 * @param weight the weight this box contributes toward its container's capacity
 */
public record BoxDef(String id, long length, long width, long height, long weight) {

    /** The volume occupied by this box (length * width * height). */
    public long volume() {
        return length * width * height;
    }
}

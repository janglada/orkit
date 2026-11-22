package io.github.janglada.orkit.cutting.internal;

/**
 * Immutable description of one demanded piece type.
 *
 * @param id        the declared piece id; instances are reported as {@code "<id>#<n>"}
 * @param length    the length of the piece (X extent)
 * @param width     the width of the piece (Y extent); {@code 0} means this piece is 1D
 * @param quantity  how many instances are demanded
 * @param rotatable whether a 2D piece may be turned 90 degrees
 */
public record PieceDef(String id, long length, int width, int quantity, boolean rotatable) {

    /** The area of one piece ({@code length * width}); {@code 0} for a 1D piece. */
    public long area() {
        return length * width;
    }
}

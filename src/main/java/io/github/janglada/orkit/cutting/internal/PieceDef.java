package io.github.janglada.orkit.cutting.internal;

/**
 * Immutable description of a single demanded piece instance (after quantity expansion).
 *
 * @param id     the unique piece identifier (post quantity-expansion, e.g. {@code "P#3"})
 * @param length the length of the piece (X extent)
 * @param width  the width of the piece (Y extent); {@code 0} means this piece is 1D
 */
public record PieceDef(String id, long length, int width) {

    /** The area of this piece ({@code length * width}); {@code 0} for a 1D piece. */
    public long area() {
        return length * width;
    }
}

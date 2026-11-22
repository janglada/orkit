package io.github.janglada.orkit.cutting.internal;

/**
 * Immutable description of a single stock roll/sheet (after quantity expansion).
 *
 * @param id     the unique roll identifier (post quantity-expansion, e.g. {@code "R#2"})
 * @param type   the declared stock id this roll was expanded from
 * @param length the usable length of the roll (X extent)
 * @param width  the usable width of the sheet (Y extent); {@code 0} means this stock is 1D
 * @param cost   the cost of consuming this roll
 */
public record StockDef(String id, String type, long length, int width, long cost) {

    /** The area of this stock ({@code length * width}); {@code 0} for a 1D roll. */
    public long area() {
        return length * width;
    }
}

package opt.cutting.internal;

/**
 * Immutable description of a single stock roll (after quantity expansion).
 *
 * @param id     the unique roll identifier (post quantity-expansion, e.g. {@code "R#2"})
 * @param length the usable length of the roll
 * @param cost   the cost of consuming this roll
 */
public record StockDef(String id, long length, long cost) {}

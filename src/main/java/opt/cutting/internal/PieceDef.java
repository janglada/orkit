package opt.cutting.internal;

/**
 * Immutable description of a single demanded piece instance (after quantity expansion).
 *
 * @param id     the unique piece identifier (post quantity-expansion, e.g. {@code "P#3"})
 * @param length the length of the piece
 */
public record PieceDef(String id, long length) {}

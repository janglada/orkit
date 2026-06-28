package opt.cutting;

import java.util.List;

/**
 * The pieces cut from a single stock roll in a solved solution.
 *
 * @param stockId the identifier of the roll (post quantity-expansion id)
 * @param length  the usable length of this roll
 * @param cost    the cost of consuming this roll
 * @param pieces  the cut pieces placed on this roll
 * @param waste   the unused length on this roll (0 for rolls with no pieces)
 */
public record StockAssignment(
        String stockId,
        long length,
        long cost,
        List<CutPiece> pieces,
        long waste
) {
    /**
     * One piece cut from a roll.
     *
     * @param pieceId the identifier of the piece instance (post quantity-expansion id)
     * @param length  the length of the piece
     * @param start   the start offset of the piece on the roll
     */
    public record CutPiece(String pieceId, long length, long start) {}
}

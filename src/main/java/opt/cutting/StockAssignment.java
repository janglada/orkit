package opt.cutting;

import java.util.List;

/**
 * The pieces cut from a single stock roll/sheet in a solved solution.
 *
 * @param stockId the identifier of the roll (post quantity-expansion id)
 * @param length  the usable length of this roll (X extent)
 * @param width   the usable width of this sheet (Y extent); {@code 0} for a 1D roll
 * @param cost    the cost of consuming this roll
 * @param pieces  the cut pieces placed on this roll
 * @param waste   the unused length (1D) or unused area (2D) on this roll
 *                (0 for rolls with no pieces)
 */
public record StockAssignment(
        String stockId,
        long length,
        long width,
        long cost,
        List<CutPiece> pieces,
        long waste
) {
    /**
     * One piece cut from a roll/sheet.
     *
     * @param pieceId the identifier of the piece instance (post quantity-expansion id)
     * @param length  the length of the piece (X extent)
     * @param x       the X (length-axis) origin of the piece on the roll
     * @param y       the Y (width-axis) origin of the piece on the sheet ({@code 0} for 1D)
     * @param width   the width of the piece (Y extent); {@code 0} for a 1D piece
     */
    public record CutPiece(String pieceId, long length, long x, long y, int width) {

        /**
         * The start offset of the piece along the roll's length axis. Kept for 1D
         * compatibility; equivalent to {@link #x()}.
         */
        public long start() {
            return x;
        }
    }
}

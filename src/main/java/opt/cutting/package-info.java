/**
 * Cutting-stock solver (1D and 2D non-guillotine).
 *
 * <p>Provides a fluent DSL for cutting demanded pieces (each with a length and quantity)
 * from stock rolls/sheets of declared dimensions, minimizing either total trim waste,
 * the number of sheets consumed, or the total stock cost. Each piece is required
 * (assigned to exactly one sheet); in 1D rolls are modelled as 1D machines and pieces
 * as optional intervals with per-roll {@code NoOverlap}, while in 2D each sheet is an
 * X/Y plane with pairwise 2D no-overlap constraints (left/right/front/back separation).
 *
 * <p><b>Dimension selection.</b> A model is 2D when at least one {@link StockConfig} or
 * {@link PieceConfig} declares a {@code .width(...)} (and then <em>every</em> stock and
 * piece must declare one); otherwise the model is 1D (the default, and the historical
 * behavior). In 2D, {@link Goals#minimizeWaste()} minimizes total <em>area</em> consumed
 * (equivalent to minimizing area waste, since total piece area is constant);
 * {@link Goals#minimizeStockUsed()} and {@link Goals#minimizeTotalCost()} work the same
 * in 1D and 2D.
 *
 * <p><b>Guillotine cutting is planned; 2D non-guillotine is supported today.</b>
 *
 * <p>Entry point: {@link opt.Optimize#cuttingStock()}.
 */
package opt.cutting;

/**
 * Cutting-stock solver: 1D rolls, and 2D sheets with free or guillotine layouts.
 *
 * <p>Provides a fluent DSL for cutting demanded pieces (each with a length and quantity)
 * from stock rolls/sheets of declared dimensions, minimizing total trim waste, the number of
 * rolls or sheets consumed, or the total stock cost. Every piece is required unless
 * {@link CuttingBuilder#allowDroppingPieces(long) dropping} is allowed.
 *
 * <p><b>Dimension selection.</b> A model is 2D when at least one {@link StockConfig} or
 * {@link PieceConfig} declares a {@code .width(...)} (and then <em>every</em> stock and
 * piece must declare one); otherwise the model is 1D. In 2D, {@link Goals#minimizeWaste()}
 * minimizes total <em>area</em> consumed; {@link Goals#minimizeStockUsed()} and
 * {@link Goals#minimizeTotalCost()} work the same in 1D and 2D.
 *
 * <p><b>Models.</b> 1D counts how many pieces of each type go on each roll, so its size
 * grows with the number of piece types, not pieces; offsets are assigned after the solve.
 * 2D places one rectangle per piece instance under {@code NoOverlap2D}, with optional
 * 90-degree {@link PieceConfig#rotatable(boolean) rotation}.
 * {@link CuttingBuilder#guillotine(boolean)} restricts 2D layouts to two-stage shelves, which
 * edge-to-edge cuts can always produce; free-form multi-stage guillotine patterns are not
 * modeled. {@link CuttingBuilder#kerf(long)} reserves the blade width between pieces.
 *
 * <p>Entry point: {@link io.github.janglada.orkit.Optimize#cuttingStock()}.
 */
package io.github.janglada.orkit.cutting;

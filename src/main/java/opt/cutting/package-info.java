/**
 * 1D cutting-stock solver.
 *
 * <p>Provides a fluent DSL for cutting demanded pieces (each with a length and quantity)
 * from stock rolls of declared lengths, minimising either total trim waste or the number
 * of rolls consumed. Each piece is required (assigned to exactly one roll); rolls are
 * modelled as 1D machines and pieces as optional intervals, so per-roll NoOverlap enforces
 * that cuts on the same roll do not overlap.
 *
 * <p>Entry point: {@link opt.Optimize#cuttingStock()}.
 */
package opt.cutting;

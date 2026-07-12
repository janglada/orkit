package opt.cutting;

/** Minimization objective for a cutting-stock problem. Create instances via {@link Goals}. */
public sealed interface CuttingGoal
        permits CuttingGoal.MinimizeWaste, CuttingGoal.MinimizeStockUsed, CuttingGoal.MinimizeTotalCost {

    /** Minimize the total trim waste (equivalently, total length of stock consumed). */
    record MinimizeWaste() implements CuttingGoal {}

    /** Minimize the number of stock rolls used. */
    record MinimizeStockUsed() implements CuttingGoal {}

    /** Minimize the sum of {@link StockConfig#cost()} over all used rolls. */
    record MinimizeTotalCost() implements CuttingGoal {}
}

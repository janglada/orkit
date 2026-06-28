package opt.cutting;

/** Minimization objective for a cutting-stock problem. Create instances via {@link Goals}. */
public sealed interface CuttingGoal
        permits CuttingGoal.MinimizeWaste, CuttingGoal.MinimizeStockUsed {

    /** Minimize the total trim waste (equivalently, total length of stock consumed). */
    record MinimizeWaste() implements CuttingGoal {}

    /** Minimize the number of stock rolls used. */
    record MinimizeStockUsed() implements CuttingGoal {}
}

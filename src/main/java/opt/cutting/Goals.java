package opt.cutting;

/** Factory for cutting-stock objectives. */
public final class Goals {

    /** Returns the goal of minimizing total trim waste (total length of stock consumed). */
    public static CuttingGoal minimizeWaste() {
        return new CuttingGoal.MinimizeWaste();
    }

    /** Returns the goal of minimizing the number of stock rolls used. */
    public static CuttingGoal minimizeStockUsed() {
        return new CuttingGoal.MinimizeStockUsed();
    }

    private Goals() {}
}

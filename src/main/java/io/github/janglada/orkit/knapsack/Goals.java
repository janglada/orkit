package io.github.janglada.orkit.knapsack;

/** Factory for multiple-knapsack objectives. */
public final class Goals {

    /** Returns the default goal: maximize the total profit of packed items. */
    public static KnapsackGoal maximizeProfit() {
        return new KnapsackGoal.MaximizeTotalProfit();
    }

    private Goals() {}
}

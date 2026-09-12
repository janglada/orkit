package opt.knapsack;

/** Objective for a multiple-knapsack problem. Create instances via {@link Goals}. */
public sealed interface KnapsackGoal permits KnapsackGoal.MaximizeTotalProfit {

    /** Maximize the sum of {@link ItemConfig#profit(long)} over all packed items. This is the default. */
    record MaximizeTotalProfit() implements KnapsackGoal {}
}

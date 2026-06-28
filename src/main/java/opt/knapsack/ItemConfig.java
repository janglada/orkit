package opt.knapsack;

import opt.ModelException;

/**
 * Fluent configurator for a single item.
 * Used inside the lambda passed to
 * {@link MultipleKnapsackBuilder#item(String, java.util.function.Consumer)}.
 */
public final class ItemConfig {

    private long profit;
    private long weight;

    ItemConfig() {}

    /** Sets the profit earned by packing this item. */
    public ItemConfig profit(long profit) {
        if (profit < 1) throw new ModelException("profit() must be >= 1, got: " + profit);
        this.profit = profit;
        return this;
    }

    /** Sets the weight this item contributes toward its bin's capacity. */
    public ItemConfig weight(long weight) {
        if (weight < 1) throw new ModelException("weight() must be >= 1, got: " + weight);
        this.weight = weight;
        return this;
    }

    long profit() {
        return profit;
    }

    long weight() {
        return weight;
    }
}

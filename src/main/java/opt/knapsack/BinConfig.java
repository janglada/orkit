package opt.knapsack;

import opt.ModelException;

/**
 * Fluent configurator for a single knapsack (bin).
 * Used inside the lambda passed to
 * {@link MultipleKnapsackBuilder#bin(String, java.util.function.Consumer)}.
 */
public final class BinConfig {

    private long capacity;

    BinConfig() {}

    public BinConfig capacity(long capacity) {
        if (capacity < 1) throw new ModelException("capacity() must be >= 1, got: " + capacity);
        this.capacity = capacity;
        return this;
    }

    long capacity() {
        return capacity;
    }
}

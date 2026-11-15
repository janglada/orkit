package io.github.janglada.orkit.knapsack;

import io.github.janglada.orkit.ModelException;

/**
 * Fluent configurator for a single knapsack (bin).
 * Used inside the lambda passed to
 * {@link MultipleKnapsackBuilder#bin(String, java.util.function.Consumer)}.
 */
public final class BinConfig {

    private long capacity;

    BinConfig() {}

    /** Sets the maximum weight this bin can hold. */
    public BinConfig capacity(long capacity) {
        if (capacity < 1) throw new ModelException("capacity() must be >= 1, got: " + capacity);
        this.capacity = capacity;
        return this;
    }

    long capacity() {
        return capacity;
    }
}

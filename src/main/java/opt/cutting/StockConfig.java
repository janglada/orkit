package opt.cutting;

import opt.ModelException;

/**
 * Fluent configurator for a single stock roll type.
 * Used inside the lambda passed to
 * {@link CuttingBuilder#stock(String, java.util.function.Consumer)}.
 */
public final class StockConfig {

    long length;
    long cost = 1;
    int quantity = 1;

    StockConfig() {}

    /** Sets the usable length of one roll of this stock. Must be {@code >= 1}. */
    public StockConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /** Sets the cost of consuming one roll of this stock. Must be {@code >= 1}. */
    public StockConfig cost(long cost) {
        if (cost < 1) throw new ModelException("cost() must be >= 1, got: " + cost);
        this.cost = cost;
        return this;
    }

    /** Sets how many rolls of this stock are available. Must be {@code >= 1}. */
    public StockConfig quantity(int quantity) {
        if (quantity < 1) throw new ModelException("quantity() must be >= 1, got: " + quantity);
        this.quantity = quantity;
        return this;
    }

    long length() {
        return length;
    }

    long cost() {
        return cost;
    }

    int quantity() {
        return quantity;
    }
}

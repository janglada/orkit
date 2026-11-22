package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

/**
 * Fluent configurator for a single stock roll type.
 * Used inside the lambda passed to
 * {@link CuttingBuilder#stock(String, java.util.function.Consumer)}.
 */
public final class StockConfig {

    String id;
    long length;
    int width = 0;
    long cost = 1;
    int quantity = 1;

    StockConfig() {}

    /** Sets the usable length of one roll of this stock. Must be {@code >= 1}. */
    public StockConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /**
     * Sets the usable width of one sheet of this stock, enabling the 2D
     * cutting path. Must be {@code >= 1}. When left unset (or set to 0) the stock is
     * treated as 1D. If any stock or piece declares a width, then every stock and piece
     * must declare one — mixed 1D/2D models are rejected at solve time.
     */
    public StockConfig width(int width) {
        Validate.atLeastOne(width, "width");
        this.width = width;
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

    String id() {
        return id;
    }

    long length() {
        return length;
    }

    int width() {
        return width;
    }

    long cost() {
        return cost;
    }

    int quantity() {
        return quantity;
    }
}

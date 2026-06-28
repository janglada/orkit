package opt.cutting;

import opt.ModelException;

/**
 * Fluent configurator for a single demanded piece type.
 * Used inside the lambda passed to
 * {@link CuttingBuilder#required(String, java.util.function.Consumer)}.
 */
public final class PieceConfig {

    long length;
    int quantity = 1;

    PieceConfig() {}

    /** Sets the length of one piece of this type. Must be {@code >= 1}. */
    public PieceConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /** Sets how many instances of this piece are demanded. Must be {@code >= 1}. */
    public PieceConfig quantity(int quantity) {
        if (quantity < 1) throw new ModelException("quantity() must be >= 1, got: " + quantity);
        this.quantity = quantity;
        return this;
    }

    long length() {
        return length;
    }

    int quantity() {
        return quantity;
    }
}

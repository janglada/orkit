package opt.cutting;

import opt.ModelException;
import opt.internal.Validate;

/**
 * Fluent configurator for a single demanded piece type.
 * Used inside the lambda passed to
 * {@link CuttingBuilder#required(String, java.util.function.Consumer)}.
 */
public final class PieceConfig {

    long length;
    int width = 0;
    int quantity = 1;

    PieceConfig() {}

    /** Sets the length of one piece of this type. Must be {@code >= 1}. */
    public PieceConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /**
     * Sets the width of one piece of this type, enabling the 2D (non-guillotine) cutting
     * path. Must be {@code >= 1}. When left unset (or set to 0) the piece is treated as
     * 1D. If any stock or piece declares a width, then every stock and piece must declare
     * one — mixed 1D/2D models are rejected at solve time.
     */
    public PieceConfig width(int width) {
        Validate.atLeastOne(width, "width");
        this.width = width;
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

    int width() {
        return width;
    }

    int quantity() {
        return quantity;
    }
}

package opt.packing;

import opt.ModelException;

/**
 * Fluent configurator for a single box.
 * Used inside the lambda passed to
 * {@link PackingBuilder#box(String, java.util.function.Consumer)}.
 */
public final class BoxConfig {

    long length;
    long width;
    long height;
    long weight;

    BoxConfig() {}

    /** Sets the length (X-axis extent) of the box. Must be {@code >= 1}. */
    public BoxConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /** Sets the width (Y-axis extent) of the box. Must be {@code >= 1}. */
    public BoxConfig width(long width) {
        if (width < 1) throw new ModelException("width() must be >= 1, got: " + width);
        this.width = width;
        return this;
    }

    /** Sets the height (Z-axis extent) of the box. Must be {@code >= 1}. */
    public BoxConfig height(long height) {
        if (height < 1) throw new ModelException("height() must be >= 1, got: " + height);
        this.height = height;
        return this;
    }

    /** Sets the weight this box contributes toward its container's capacity. Must be {@code >= 1}. */
    public BoxConfig weight(long weight) {
        if (weight < 1) throw new ModelException("weight() must be >= 1, got: " + weight);
        this.weight = weight;
        return this;
    }

    long length()  { return length; }
    long width()   { return width; }
    long height()  { return height; }
    long weight()  { return weight; }
}

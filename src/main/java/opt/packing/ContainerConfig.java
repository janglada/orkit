package opt.packing;

import opt.ModelException;

/**
 * Fluent configurator for a single container.
 * Used inside the lambda passed to
 * {@link PackingBuilder#container(String, java.util.function.Consumer)}.
 */
public final class ContainerConfig {

    long length;
    long width;
    long height;
    long maxWeight;

    ContainerConfig() {}

    /** Sets the interior length (X-axis extent) of the container. Must be {@code >= 1}. */
    public ContainerConfig length(long length) {
        if (length < 1) throw new ModelException("length() must be >= 1, got: " + length);
        this.length = length;
        return this;
    }

    /** Sets the interior width (Y-axis extent) of the container. Must be {@code >= 1}. */
    public ContainerConfig width(long width) {
        if (width < 1) throw new ModelException("width() must be >= 1, got: " + width);
        this.width = width;
        return this;
    }

    /** Sets the interior height (Z-axis extent) of the container. Must be {@code >= 1}. */
    public ContainerConfig height(long height) {
        if (height < 1) throw new ModelException("height() must be >= 1, got: " + height);
        this.height = height;
        return this;
    }

    /** Sets the maximum total weight the container may hold. Must be {@code >= 1}. */
    public ContainerConfig maxWeight(long maxWeight) {
        if (maxWeight < 1) throw new ModelException("maxWeight() must be >= 1, got: " + maxWeight);
        this.maxWeight = maxWeight;
        return this;
    }

    long length()     { return length; }
    long width()      { return width; }
    long height()     { return height; }
    long maxWeight()  { return maxWeight; }
}

package opt.packing;

import opt.ModelException;
import opt.internal.Validate;

import java.util.ArrayList;
import java.util.List;

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
    boolean rotatable = false;
    long maxStackedWeight = Long.MAX_VALUE;
    final List<String> aboveIds = new ArrayList<>();
    final List<String> belowIds = new ArrayList<>();

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

    /**
     * Allows the solver to reorient this box, choosing any distinct axis-permutation of
     * its {@code (length, width, height)} dimensions (up to six; a cube has one). Disabled
     * by default, which keeps the canonical orientation fixed.
     */
    public BoxConfig rotatable(boolean rotatable) {
        this.rotatable = rotatable;
        return this;
    }

    /**
     * Caps the total weight of boxes that rest directly on top of this one. The default
     * {@code Long.MAX_VALUE} leaves the load unbounded; passing a finite value turns on
     * the weight-bearing constraint for this box. Must be {@code >= 0}.
     */
    public BoxConfig maxStackedWeight(long maxStackedWeight) {
        Validate.nonNegative(maxStackedWeight, "maxStackedWeight()");
        this.maxStackedWeight = maxStackedWeight;
        return this;
    }

    /**
     * Declares that this box must sit directly above the box with the given id (this box
     * rests on top of {@code otherId}). The referenced id must exist among the declared
     * boxes. A pair declared in both directions (A above B and A below B) is a
     * contradiction and is rejected at solve time.
     */
    public BoxConfig above(String otherId) {
        Validate.nonBlank(otherId, "above() id");
        this.aboveIds.add(otherId);
        return this;
    }

    /**
     * Declares that this box must sit directly below the box with the given id
     * ({@code otherId} rests on top of this one). The referenced id must exist among the
     * declared boxes. A pair declared in both directions is a contradiction.
     */
    public BoxConfig below(String otherId) {
        Validate.nonBlank(otherId, "below() id");
        this.belowIds.add(otherId);
        return this;
    }

    long length()            { return length; }
    long width()             { return width; }
    long height()            { return height; }
    long weight()            { return weight; }
    boolean rotatable()      { return rotatable; }
    long maxStackedWeight()  { return maxStackedWeight; }
    List<String> aboveIds()  { return aboveIds; }
    List<String> belowIds()  { return belowIds; }
}

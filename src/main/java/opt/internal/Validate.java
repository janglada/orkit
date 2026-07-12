package opt.internal;

import opt.ModelException;

/**
 * Tiny validation helpers for builder guard clauses.
 *
 * <p>These replace duplicated inline null/blank/numeric checks across the
 * domain builders. Message formats are chosen to preserve the substrings
 * that existing tests assert (e.g. {@code "non-blank"}, {@code ">= 0"}).
 */
public final class Validate {

    /** Throws if {@code value} is null or blank. */
    public static void nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ModelException(name + " must be non-blank.");
        }
    }

    /** Throws if {@code v} is negative. */
    public static void nonNegative(long v, String name) {
        if (v < 0) {
            throw new ModelException(name + " must be >= 0, got: " + v);
        }
    }

    /** Throws if {@code v} is zero or negative. */
    public static void positive(long v, String name) {
        if (v <= 0) {
            throw new ModelException(name + " must be > 0, got: " + v);
        }
    }

    /** Throws if {@code v} is less than one. */
    public static void atLeastOne(long v, String name) {
        if (v < 1) {
            throw new ModelException(name + " must be >= 1, got: " + v);
        }
    }

    private Validate() {}
}
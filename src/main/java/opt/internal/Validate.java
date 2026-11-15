package opt.internal;

import opt.ModelException;

import java.time.Duration;

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

    /** Throws if {@code d} is null, zero, or negative. */
    public static void positive(Duration d, String name) {
        if (d == null) throw new ModelException(name + " must not be null.");
        if (d.isZero() || d.isNegative()) {
            throw new ModelException(name + " must be > 0, got: " + d);
        }
    }

    /** {@code a + b}, or a {@link ModelException} naming {@code what} if it overflows a long. */
    public static long add(long a, long b, String what) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw overflow(what);
        }
    }

    /** {@code a * b}, or a {@link ModelException} naming {@code what} if it overflows a long. */
    public static long multiply(long a, long b, String what) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw overflow(what);
        }
    }

    private static ModelException overflow(String what) {
        return new ModelException(what + " overflows a 64-bit integer; the input values are too large.");
    }

    private Validate() {}
}
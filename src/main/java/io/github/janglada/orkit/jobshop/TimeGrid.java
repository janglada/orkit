package io.github.janglada.orkit.jobshop;

import io.github.janglada.orkit.ModelException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Converts calendar times to model time units: unit {@code n} starts at
 * {@code origin + n * unit}. Values off the grid are rejected, not rounded.
 *
 * @param origin wall-clock time of model time 0
 * @param unit   length of one model time unit
 */
record TimeGrid(LocalDateTime origin, Duration unit) {

    /** Model units of a duration. */
    long units(Duration d, String what) {
        if (d == null) throw new ModelException(what + " must not be null.");
        return exact(d, what);
    }

    /** Model time of a wall-clock instant; must not be before the origin. */
    long units(LocalDateTime t, String what) {
        if (t == null) throw new ModelException(what + " must not be null.");
        if (t.isBefore(origin)) {
            throw new ModelException(what + " " + t + " is before the schedule origin " + origin + ".");
        }
        return exact(Duration.between(origin, t), what);
    }

    /** Wall-clock instant of a model time. */
    LocalDateTime at(long units) {
        return origin.plus(unit.multipliedBy(units));
    }

    private long exact(Duration d, String what) {
        long units;
        try {
            units = d.dividedBy(unit);
            if (!unit.multipliedBy(units).equals(d)) {
                throw new ModelException(what + " " + d + " is not a whole number of time units (" + unit + ").");
            }
        } catch (ArithmeticException e) {
            throw new ModelException(what + " " + d + " is too many time units (" + unit + ").");
        }
        return units;
    }
}

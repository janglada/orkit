package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.ModelException;

import java.time.LocalDateTime;

/**
 * A half-open time window {@code [start, end)} on the absolute time-line.
 *
 * @param start the inclusive start of the window
 * @param end   the exclusive end of the window; must be strictly after {@code start}
 */
public record TimeSlot(LocalDateTime start, LocalDateTime end) {
    /** Validates that {@code end} is strictly after {@code start}. */
    public TimeSlot {
        if (!end.isAfter(start)) {
            throw new ModelException("TimeSlot end must be after start, got " + start + " to " + end);
        }
    }
}

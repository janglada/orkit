package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Fluent configurator for a single appointment.
 * Used inside the lambda passed to
 * {@link BookingBuilder#appointment(String, java.util.function.Consumer)}.
 *
 * <p>An appointment must declare a {@linkplain #duration(Duration) duration} and a
 * non-empty {@linkplain #requiresAnyOf(String...) set of candidate resources}; an
 * optional {@linkplain #earliest(LocalDateTime) earliest} / {@linkplain #latest(LocalDateTime) latest}
 * window and an {@linkplain #penaltyIfUnscheduled(long) unscheduled penalty} may be set.
 */
public final class AppointmentConfig {

    Duration duration;
    String[] resourceIds;
    LocalDateTime earliestDt;
    LocalDateTime latestDt;
    long penalty = 1L;
    Duration buffer = Duration.ZERO;

    AppointmentConfig() {}

    /** Sets how long the appointment occupies its chosen resource. Must be at least one minute. */
    public AppointmentConfig duration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 1) {
            throw new ModelException("duration() must be >= 1 minute, got: " + d);
        }
        this.duration = d;
        return this;
    }

    /** Declares the candidate resources; the appointment will be booked on at most one of them. */
    public AppointmentConfig requiresAnyOf(String... ids) {
        if (ids == null || ids.length == 0) {
            throw new ModelException("requiresAnyOf() requires at least one resource id.");
        }
        for (String id : ids) {
            Validate.nonBlank(id, "requiresAnyOf() resource id");
        }
        this.resourceIds = ids.clone();
        return this;
    }

    /** Earliest wall-clock time at which the appointment may start. */
    public AppointmentConfig earliest(LocalDateTime dt) {
        this.earliestDt = dt;
        return this;
    }

    /** Latest wall-clock time by which the appointment must end. */
    public AppointmentConfig latest(LocalDateTime dt) {
        this.latestDt = dt;
        return this;
    }

    /**
     * Turnaround time the resource stays blocked after the appointment (cleanup, travel). It is
     * not part of the reported {@link AppointmentAssignment#end()} and may spill past the
     * resource's availability window or the appointment's {@link #latest(LocalDateTime)}, but
     * never overlaps another appointment on the same resource. Default zero.
     */
    public AppointmentConfig buffer(Duration d) {
        if (d == null || d.isNegative()) throw new ModelException("buffer() must be non-negative, got: " + d);
        this.buffer = d;
        return this;
    }

    /** Penalty incurred if this appointment cannot be scheduled. Defaults to {@code 1}. */
    public AppointmentConfig penaltyIfUnscheduled(long p) {
        if (p < 0) {
            throw new ModelException("penaltyIfUnscheduled() must be >= 0, got: " + p);
        }
        this.penalty = p;
        return this;
    }

    Duration duration()        { return duration; }
    String[] resourceIds()     { return resourceIds; }
    LocalDateTime earliestDt() { return earliestDt; }
    LocalDateTime latestDt()   { return latestDt; }
    long penalty()             { return penalty; }
    Duration buffer()          { return buffer; }
}

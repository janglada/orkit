package opt.booking;

import opt.ModelException;

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
}

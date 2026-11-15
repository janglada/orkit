package io.github.janglada.orkit.booking;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * One appointment placed on a resource in a solved {@link BookingSolution}.
 *
 * @param appointmentId  the id of the appointment, as declared in the builder
 * @param resourceId     the id of the resource the appointment was booked on
 * @param start          wall-clock start of the appointment
 * @param end            wall-clock end of the appointment ({@code start + duration}; any
 *                       buffer declared via {@code AppointmentConfig.buffer} is not included)
 */
public record AppointmentAssignment(
        String appointmentId,
        String resourceId,
        LocalDateTime start,
        LocalDateTime end
) {
    /** The booked duration, {@code end - start}. */
    public Duration duration() {
        return Duration.between(start, end);
    }
}

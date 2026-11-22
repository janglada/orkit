package io.github.janglada.orkit.booking;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One appointment placed on a resource in a solved {@link BookingSolution}.
 *
 * @param appointmentId  the id of the appointment, as declared in the builder
 * @param resourceId     the resource booked for the main requirement
 *                       ({@link AppointmentConfig#requiresAnyOf(String...)}, or the first one declared)
 * @param start          wall-clock start of the appointment
 * @param end            wall-clock end of the appointment ({@code start + duration}; any
 *                       buffer declared via {@code AppointmentConfig.buffer} is not included)
 * @param resourceIds    every resource booked, one per requirement in declaration order
 */
public record AppointmentAssignment(
        String appointmentId,
        String resourceId,
        LocalDateTime start,
        LocalDateTime end,
        List<String> resourceIds
) {
    /** Copies the resource list. */
    public AppointmentAssignment {
        resourceIds = List.copyOf(resourceIds);
    }

    /** The booked duration, {@code end - start}. */
    public Duration duration() {
        return Duration.between(start, end);
    }
}

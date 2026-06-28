package opt.booking;

/**
 * One appointment placed on a resource in a solved {@link BookingSolution}.
 *
 * @param appointmentId  the id of the appointment, as declared in the builder
 * @param resourceId     the id of the resource the appointment was booked on
 * @param startMinute    minute offset from the solver epoch at which the appointment starts
 * @param endMinute      minute offset from the solver epoch at which the appointment ends
 */
public record AppointmentAssignment(
        String appointmentId,
        String resourceId,
        long startMinute,
        long endMinute
) {}

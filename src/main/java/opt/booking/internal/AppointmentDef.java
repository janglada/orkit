package opt.booking.internal;

import java.util.List;

/**
 * Internal definition of an appointment, with all times expressed as minute offsets
 * from the solver epoch.
 *
 * @param id              unique appointment id
 * @param durationMinutes how long the appointment occupies its chosen resource
 * @param resourceIds     candidate resources; the appointment is booked on at most one
 * @param earliestOffset  earliest start minute, or {@code null} for "no lower bound"
 * @param latestOffset    latest end minute, or {@code null} for "no upper bound"
 * @param penalty         penalty incurred if this appointment is left unscheduled
 */
public record AppointmentDef(String id, long durationMinutes, List<String> resourceIds,
                             Long earliestOffset, Long latestOffset, long penalty) {
    /** Defensive copy of the candidate-resource list. */
    public AppointmentDef {
        resourceIds = List.copyOf(resourceIds);
    }
}

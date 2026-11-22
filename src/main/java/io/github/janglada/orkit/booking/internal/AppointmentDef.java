package io.github.janglada.orkit.booking.internal;

import java.util.List;

/**
 * Internal definition of an appointment, with all times expressed as minute offsets
 * from the solver epoch.
 *
 * @param id              unique appointment id
 * @param durationMinutes how long the appointment occupies its chosen resource
 * @param bufferMinutes   extra time the resource stays blocked after the appointment
 * @param resourceGroups  requirement groups; when scheduled, one resource of every group is booked
 *                        at the same time
 * @param earliestOffset  earliest start minute, or {@code null} for "no lower bound"
 * @param latestOffset    latest end minute, or {@code null} for "no upper bound"
 * @param penalty         penalty incurred if this appointment is left unscheduled
 */
public record AppointmentDef(String id, long durationMinutes, long bufferMinutes, List<List<String>> resourceGroups,
                             Long earliestOffset, Long latestOffset, long penalty) {
    /** Defensive copy of the requirement groups. */
    public AppointmentDef {
        resourceGroups = resourceGroups.stream().map(List::copyOf).toList();
    }
}

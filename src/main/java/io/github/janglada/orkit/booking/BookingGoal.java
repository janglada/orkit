package io.github.janglada.orkit.booking;

/**
 * Objective for a resource-booking problem: either maximize the number of appointments
 * that get scheduled, or minimize the total penalty of appointments left unscheduled.
 *
 * @see Goals
 */
public sealed interface BookingGoal {
    /** Maximize the count of scheduled appointments (every appointment counts equally). */
    record MaximizeScheduledAppointments() implements BookingGoal {}
    /** Minimize the sum of {@code penalty} over appointments that could not be scheduled. */
    record MinimizeUnscheduledPenalty() implements BookingGoal {}
}

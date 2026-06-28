package opt.booking;

/** Factory methods for the {@link BookingGoal} objectives supported by the booking solver. */
public final class Goals {

    private Goals() {}

    /** Maximize the number of appointments that are scheduled. */
    public static BookingGoal maximizeScheduledAppointments() {
        return new BookingGoal.MaximizeScheduledAppointments();
    }

    /** Minimize the total penalty of appointments left unscheduled. */
    public static BookingGoal minimizeUnscheduledPenalty() {
        return new BookingGoal.MinimizeUnscheduledPenalty();
    }
}

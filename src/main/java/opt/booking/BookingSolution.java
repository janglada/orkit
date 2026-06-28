package opt.booking;

import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/** The solved result of a resource-booking problem. */
public final class BookingSolution implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<AppointmentAssignment> assignments;
    private final List<String> unscheduled;

    /** Constructs a solved booking result; normally produced by {@link opt.Solver#solve()}, not user code. */
    public BookingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<AppointmentAssignment> assignments, List<String> unscheduled) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(new ArrayList<>(assignments));
        this.unscheduled = Collections.unmodifiableList(new ArrayList<>(unscheduled));
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }

    /** All appointments that were successfully booked, in appointment-declaration order. */
    public List<AppointmentAssignment> assignments() {
        return assignments;
    }

    /** Ids of appointments that could not be scheduled on any resource. */
    public List<String> unscheduled() {
        return unscheduled;
    }

    /** The assignments whose resource id equals {@code resourceId}. */
    public List<AppointmentAssignment> assignmentsOn(String resourceId) {
        List<AppointmentAssignment> out = new ArrayList<>();
        for (AppointmentAssignment a : assignments) {
            if (a.resourceId().equals(resourceId)) {
                out.add(a);
            }
        }
        return out;
    }
}

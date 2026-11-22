package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/** The solved result of a resource-booking problem. */
public final class BookingSolution implements Solution {

    private final SolveStatus status;
    private final SolveDiagnostics diagnostics;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<AppointmentAssignment> assignments;
    private final List<String> unscheduled;

    /** Creates a result without search statistics ({@link SolveDiagnostics#none()}). */
    public BookingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<AppointmentAssignment> assignments, List<String> unscheduled) {
        this(status, objective, runtime, assignments, unscheduled, SolveDiagnostics.none());
    }

    /** Constructs a solved booking result; normally produced by {@link io.github.janglada.orkit.Solver#solve()}, not user code. */
    public BookingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<AppointmentAssignment> assignments, List<String> unscheduled,
                           SolveDiagnostics diagnostics) {
        this.status = status;
        this.diagnostics = (diagnostics == null) ? SolveDiagnostics.none() : diagnostics;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(new ArrayList<>(assignments));
        this.unscheduled = Collections.unmodifiableList(new ArrayList<>(unscheduled));
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }
    /** Search statistics reported by CP-SAT for this result. */
    @Override public SolveDiagnostics diagnostics() { return diagnostics; }

    /** All appointments that were successfully booked, in appointment-declaration order. */
    public List<AppointmentAssignment> assignments() {
        return assignments;
    }

    /** Ids of appointments that could not be scheduled on any resource. */
    public List<String> unscheduled() {
        return unscheduled;
    }

    /** The assignments that book {@code resourceId} for any of their requirements. */
    public List<AppointmentAssignment> assignmentsOn(String resourceId) {
        List<AppointmentAssignment> out = new ArrayList<>();
        for (AppointmentAssignment a : assignments) {
            if (a.resourceIds().contains(resourceId)) {
                out.add(a);
            }
        }
        return out;
    }
}

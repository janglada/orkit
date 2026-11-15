package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/** The solved result of an employee scheduling problem. */
public final class EmployeeRoster implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<ShiftAssignment> assignments;

    /** Creates a roster from the solver's raw output; the assignment list is defensively copied. */
    public EmployeeRoster(SolveStatus status, OptionalDouble objective,
                          Duration runtime, List<ShiftAssignment> assignments) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(assignments);
    }

    /** Returns the solver's termination status for this roster. */
    @Override public SolveStatus status()       { return status; }
    /** Returns the objective value, or an empty {@code OptionalDouble} if no objective was optimized. */
    @Override public OptionalDouble objective() { return objective; }
    /** Returns the wall-clock time spent solving. */
    @Override public Duration runtime()         { return runtime; }

    /** Returns the immutable list of employee-to-shift assignments. */
    public List<ShiftAssignment> assignments() {
        return assignments;
    }

    /**
     * Returns the ids of all shifts assigned to the given employee, one entry per assignment.
     * A recurring shift appears once per date the employee works it.
     */
    public List<String> shiftsFor(String employeeId) {
        return assignments.stream()
                .filter(a -> a.employee().equals(employeeId))
                .map(ShiftAssignment::shift)
                .collect(Collectors.toList());
    }

    /**
     * Returns the ids of all employees assigned to the given shift, across every date it
     * occurs on. Use {@link #staffOf(String, LocalDate)} for one occurrence.
     */
    public List<String> staffOf(String shiftId) {
        return assignments.stream()
                .filter(a -> a.shift().equals(shiftId))
                .map(ShiftAssignment::employee)
                .collect(Collectors.toList());
    }

    /** Returns the ids of the employees assigned to the given shift on the given date. */
    public List<String> staffOf(String shiftId, LocalDate date) {
        return assignments.stream()
                .filter(a -> a.shift().equals(shiftId) && a.date().equals(date))
                .map(ShiftAssignment::employee)
                .collect(Collectors.toList());
    }

    /** Returns every assignment falling on the given date. */
    public List<ShiftAssignment> assignmentsOn(LocalDate date) {
        return assignments.stream()
                .filter(a -> a.date().equals(date))
                .collect(Collectors.toList());
    }
}

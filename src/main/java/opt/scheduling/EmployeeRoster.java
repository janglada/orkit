package opt.scheduling;

import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
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

    /** Returns the ids of all shifts assigned to the given employee. */
    public List<String> shiftsFor(String employeeId) {
        return assignments.stream()
                .filter(a -> a.employee().equals(employeeId))
                .map(ShiftAssignment::shift)
                .collect(Collectors.toList());
    }

    /** Returns the ids of all employees assigned to the given shift. */
    public List<String> staffOf(String shiftId) {
        return assignments.stream()
                .filter(a -> a.shift().equals(shiftId))
                .map(ShiftAssignment::employee)
                .collect(Collectors.toList());
    }
}

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

    public EmployeeRoster(SolveStatus status, OptionalDouble objective,
                          Duration runtime, List<ShiftAssignment> assignments) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(assignments);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }

    public List<ShiftAssignment> assignments() {
        return assignments;
    }

    public List<String> shiftsFor(String employeeId) {
        return assignments.stream()
                .filter(a -> a.employee().equals(employeeId))
                .map(ShiftAssignment::shift)
                .collect(Collectors.toList());
    }

    public List<String> staffOf(String shiftId) {
        return assignments.stream()
                .filter(a -> a.shift().equals(shiftId))
                .map(ShiftAssignment::employee)
                .collect(Collectors.toList());
    }
}

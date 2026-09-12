package opt.scheduling.internal;

/**
 * A fixed decision on one (employee, shift instance) pair.
 *
 * @param employeeId      the employee
 * @param shiftInstanceId the {@link ShiftDef#id()} of the instance
 * @param assign          {@code true} forces the assignment, {@code false} forbids it
 */
public record LockDef(String employeeId, String shiftInstanceId, boolean assign) {}

package io.github.janglada.orkit.jobshop;

/**
 * A solved operation with its start and end times.
 *
 * @param job     identifier of the job this operation belongs to
 * @param index   position of this operation within its job's ordered operation sequence
 * @param machine machine the operation was assigned to (chosen from candidates for flexible operations)
 * @param start   start time of the operation, in model time units
 * @param end     end time of the operation, in model time units
 */
public record ScheduledOperation(
        String job,
        int index,
        String machine,
        long start,
        long end
) {}

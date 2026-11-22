package io.github.janglada.orkit.jobshop;

import java.time.LocalDateTime;

/**
 * A solved operation with its start and end times.
 *
 * @param job     identifier of the job this operation belongs to
 * @param index   position of this operation within its job's ordered operation sequence
 * @param machine machine the operation was assigned to (chosen from candidates for flexible operations)
 * @param start   start time of the operation, in model time units
 * @param end     end time of the operation, in model time units
 * @param startTime wall-clock start; {@code null} unless the builder declared
 *                  {@link JobShopBuilder#startingAt(java.time.LocalDateTime) a calendar}
 * @param endTime   wall-clock end; {@code null} without a calendar
 */
public record ScheduledOperation(
        String job,
        int index,
        String machine,
        long start,
        long end,
        LocalDateTime startTime,
        LocalDateTime endTime
) {}

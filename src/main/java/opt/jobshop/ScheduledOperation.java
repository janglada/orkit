package opt.jobshop;

/** A solved operation with its start and end times. */
public record ScheduledOperation(
        /** Identifier of the job this operation belongs to. */
        String job,
        /** Position of this operation within its job's ordered operation sequence. */
        int index,
        /** Machine the operation was assigned to (chosen from candidates for flexible operations). */
        String machine,
        /** Start time of the operation, in model time units. */
        long start,
        /** End time of the operation, in model time units. */
        long end
) {}

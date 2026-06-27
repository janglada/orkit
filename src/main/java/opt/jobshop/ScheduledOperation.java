package opt.jobshop;

/** A solved operation with its start and end times. */
public record ScheduledOperation(
        String job,
        int index,
        String machine,
        long start,
        long end
) {}

package opt.jobshop.internal;

import java.util.List;

/** Internal definition of a single operation within a job. */
public record OperationDef(
        /** Position of this operation within its job's ordered sequence. */
        int index,
        /** Single machine, or multiple candidates for flexible job shop. */
        List<String> machines,
        /** Processing duration in model time units. */
        int duration,
        /** Setup type tag used for sequence-dependent changeover; {@code null} if untyped. */
        String type,
        /** Earliest start time (release date); {@code null} if unconstrained. */
        Long releaseTime,
        /** Latest end time (hard deadline); {@code null} if unconstrained. */
        Long deadline,
        /** Soft due date for tardiness penalties (compared against this op's end); {@code null} if unconstrained. */
        Long dueTime
) {
    /** Returns {@code true} if this operation may run on more than one candidate machine. */
    public boolean isFlexible() {
        return machines.size() > 1;
    }

    /** Returns the single assigned machine; valid only when {@link #isFlexible()} is {@code false}. */
    public String machine() {
        if (isFlexible()) throw new IllegalStateException("Flexible operation; use machines().");
        return machines.get(0);
    }
}

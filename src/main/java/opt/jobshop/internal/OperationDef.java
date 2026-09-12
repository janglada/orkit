package opt.jobshop.internal;

import java.util.List;

/**
 * Internal definition of a single operation within a job.
 *
 * @param index               position of this operation within its job's ordered sequence
 * @param machines            single machine, or multiple candidates for flexible job shop
 * @param duration            processing duration in model time units
 * @param type                setup type tag used for sequence-dependent changeover; {@code null} if untyped
 * @param releaseTime         earliest start time (release date); {@code null} if unconstrained
 * @param deadline            latest end time (hard deadline); {@code null} if unconstrained
 * @param dueTime             soft due date for tardiness penalties; {@code null} if unconstrained
 * @param maxLagAfterPrevious maximum idle time allowed between the previous operation's end and this
 *                            one's start; {@code null} for unbounded, {@code 0} for no-wait
 */
public record OperationDef(
        int index,
        List<String> machines,
        int duration,
        String type,
        Long releaseTime,
        Long deadline,
        Long dueTime,
        Long maxLagAfterPrevious
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

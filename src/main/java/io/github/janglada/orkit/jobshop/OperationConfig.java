package io.github.janglada.orkit.jobshop;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fluent configurator for a single job operation.
 * Returned by {@link JobConfig#operation()} and {@link OperationConfig#then()}.
 */
public final class OperationConfig {

    private final JobConfig job;
    private List<String> machines;
    private int duration;
    private String type;
    private Long releaseTime;
    private Long deadline;
    private Long dueTime;
    private Long maxLagAfterPrevious;

    OperationConfig(JobConfig job) {
        this.job = job;
    }

    /** Assigns this operation to a single fixed machine. */
    public OperationConfig on(String machine) {
        this.machines = List.of(machine);
        return this;
    }

    /**
     * Tags this operation with a setup type, so that sequence-dependent setup
     * (changeover) times apply between adjacent operations of different types
     * on the same machine. Operations without a type pay no setup.
     */
    public OperationConfig type(String type) {
        this.type = (type == null || type.isBlank()) ? null : type;
        return this;
    }

    /** Marks this operation as flexible, allowing it to run on any of the given candidate machines. */
    public OperationConfig onAnyOf(String... machines) {
        // Dedupe candidate machines while preserving declaration order.
        Set<String> unique = new LinkedHashSet<>(Arrays.asList(machines));
        if (unique.size() < 2) {
            throw new ModelException("onAnyOf() requires at least 2 distinct candidate machines");
        }
        this.machines = List.copyOf(unique);
        return this;
    }

    /**
     * Sets the processing duration of this operation in model time units. The library does
     * not fix a unit: pick one (minutes, hours, shifts) and use it consistently for
     * {@code takes}, {@link #releaseAt(long)}, {@link #deadlineAt(long)}, {@link #dueBy(long)},
     * setup times, and the {@code start}/{@code end} values reported in the schedule.
     */
    public OperationConfig takes(int durationUnits) {
        if (durationUnits < 1) throw new ModelException("takes() must be >= 1, got: " + durationUnits);
        this.duration = durationUnits;
        return this;
    }

    /**
     * Sets the earliest start time (release date) of this operation. The
     * operation's start variable is bounded below by {@code releaseTime}. May
     * be combined with a {@link #deadlineAt(long) deadline}; the two must leave
     * room for the processing duration.
     */
    public OperationConfig releaseAt(long releaseTime) {
        Validate.nonNegative(releaseTime, "release time");
        this.releaseTime = releaseTime;
        return this;
    }

    /**
     * Sets the latest end time (hard deadline) of this operation. The
     * operation's end variable is bounded above by {@code deadline}. A deadline
     * tighter than the unconstrained optimum forces the schedule to compress.
     */
    public OperationConfig deadlineAt(long deadline) {
        Validate.nonNegative(deadline, "deadline");
        this.deadline = deadline;
        return this;
    }

    /**
     * Sets a soft due date for this operation. Tardiness
     * {@code max(0, end - due)} is penalized in the objective when a tardiness
     * goal is selected (see {@link io.github.janglada.orkit.jobshop.Goals}). Has no effect under the
     * default {@code MinimizeMakespan} goal.
     */
    public OperationConfig dueBy(long dueTime) {
        Validate.nonNegative(dueTime, "due time");
        this.dueTime = dueTime;
        return this;
    }

    /**
     * Caps the idle time between the previous operation's end and this operation's start,
     * overriding the job-level {@link JobConfig#maxLag(long)}. Not allowed on a job's first
     * operation. {@code 0} means the operation must start exactly when its predecessor ends.
     */
    public OperationConfig maxLagAfterPrevious(long lag) {
        Validate.nonNegative(lag, "maxLagAfterPrevious");
        this.maxLagAfterPrevious = lag;
        return this;
    }

    /** Finalizes this operation and returns a new one for the next step in the job. */
    public OperationConfig then() {
        job.commitOperation(this);
        OperationConfig next = new OperationConfig(job);
        job.activeOp = next;
        return next;
    }

    List<String> machines() {
        return machines;
    }

    int duration() {
        return duration;
    }

    String type() {
        return type;
    }

    Long releaseTime() {
        return releaseTime;
    }

    Long deadline() {
        return deadline;
    }

    Long dueTime() {
        return dueTime;
    }

    Long maxLagAfterPrevious() {
        return maxLagAfterPrevious;
    }
}

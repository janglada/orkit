package io.github.janglada.orkit.jobshop;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.jobshop.internal.OperationDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent configurator for a job, used inside the lambda passed to
 * {@link JobShopBuilder#job(String, java.util.function.Consumer)}.
 */
public final class JobConfig {

    final List<OperationDef> committed = new ArrayList<>();
    OperationConfig activeOp;
    long weight = 1;
    Long defaultMaxLag;
    final TimeGrid grid;

    JobConfig(TimeGrid grid) {
        this.grid = grid;
    }

    /** The calendar of the builder, or a {@link ModelException} when none was declared. */
    TimeGrid grid(String what) {
        if (grid == null) {
            throw new ModelException(what + " needs a calendar: call JobShopBuilder.startingAt(...) before declaring jobs.");
        }
        return grid;
    }

    /**
     * Sets this job's relative importance (default 1). Multiplies the job's tardiness terms
     * under tardiness goals and its completion time under
     * {@link Goals#minimizeWeightedCompletionTime()}.
     */
    public JobConfig weight(long weight) {
        Validate.positive(weight, "job weight");
        this.weight = weight;
        return this;
    }

    /**
     * Caps the idle time between consecutive operations of this job: each operation must
     * start within {@code lag} time units after its predecessor ends. Overridable per
     * operation with {@link OperationConfig#maxLagAfterPrevious(long)}.
     */
    public JobConfig maxLag(long lag) {
        Validate.nonNegative(lag, "maxLag");
        this.defaultMaxLag = lag;
        return this;
    }

    /** No idle time between consecutive operations of this job; alias of {@code maxLag(0)}. */
    public JobConfig noWait() {
        return maxLag(0);
    }

    /**
     * Starts the first operation of this job. Subsequent operations are added with
     * {@link OperationConfig#then()}; calling {@code operation()} again while an
     * operation is still pending is an error rather than a silent replacement.
     */
    public OperationConfig operation() {
        if (activeOp != null) {
            throw new ModelException("operation() was already called for this job; use then() to add the next operation.");
        }
        activeOp = new OperationConfig(this);
        return activeOp;
    }

    void commitOperation(OperationConfig op) {
        if (op.machines() == null || op.machines().isEmpty()) {
            throw new ModelException(
                    "Operation " + committed.size() + " must specify a machine via .on() or .onAnyOf().");
        }
        if (op.duration() < 1) {
            throw new ModelException(
                    "Operation " + committed.size() + " must set a duration via .takes(...).");
        }
        Long release = op.releaseTime();
        Long deadline = op.deadline();
        if (release != null && deadline != null && release + op.duration() > deadline) {
            throw new ModelException(
                    "Operation " + committed.size()
                            + " release (" + release + ") + duration (" + op.duration()
                            + ") exceeds deadline (" + deadline
                            + "); the operation cannot fit in its time window.");
        }
        Long lag = op.maxLagAfterPrevious() != null ? op.maxLagAfterPrevious() : defaultMaxLag;
        if (op.maxLagAfterPrevious() != null && committed.isEmpty()) {
            throw new ModelException("maxLagAfterPrevious() is not allowed on the first operation of a job.");
        }
        committed.add(new OperationDef(committed.size(), op.machines(), op.duration(), op.type(),
                release, deadline, op.dueTime(), committed.isEmpty() ? null : lag));
    }

    /** Called by the builder after the lambda returns to commit the last pending operation. */
    void commitPending() {
        if (activeOp != null) {
            commitOperation(activeOp);
            activeOp = null;
        }
    }
}

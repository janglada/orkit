package opt.jobshop;

import opt.jobshop.internal.OperationDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent configurator for a job, used inside the lambda passed to
 * {@link JobShopBuilder#job(String, java.util.function.Consumer)}.
 */
public final class JobConfig {

    final List<OperationDef> committed = new ArrayList<>();
    OperationConfig activeOp;

    JobConfig() {}

    /** Starts the first (or next) operation in this job. */
    public OperationConfig operation() {
        activeOp = new OperationConfig(this);
        return activeOp;
    }

    void commitOperation(OperationConfig op) {
        if (op.machines() == null || op.machines().isEmpty()) {
            throw new IllegalStateException("Operation must specify a machine via .on() or .onAnyOf().");
        }
        committed.add(new OperationDef(committed.size(), op.machines(), op.duration(), op.type()));
    }

    /** Called by the builder after the lambda returns to commit the last pending operation. */
    void commitPending() {
        if (activeOp != null) {
            commitOperation(activeOp);
            activeOp = null;
        }
    }
}

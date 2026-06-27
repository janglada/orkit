package opt.jobshop;

import java.util.Arrays;
import java.util.List;

/**
 * Fluent configurator for a single job operation.
 * Returned by {@link JobConfig#operation()} and {@link OperationConfig#then()}.
 */
public final class OperationConfig {

    private final JobConfig job;
    private List<String> machines;
    private int duration;

    OperationConfig(JobConfig job) {
        this.job = job;
    }

    public OperationConfig on(String machine) {
        this.machines = List.of(machine);
        return this;
    }

    public OperationConfig onAnyOf(String... machines) {
        this.machines = List.copyOf(Arrays.asList(machines));
        return this;
    }

    public OperationConfig takes(int durationUnits) {
        this.duration = durationUnits;
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
}

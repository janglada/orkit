package opt.jobshop;

import opt.ModelException;

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

    /** Sets the processing duration of this operation in model time units. */
    public OperationConfig takes(int durationUnits) {
        if (durationUnits < 1) throw new ModelException("takes() must be >= 1, got: " + durationUnits);
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

    String type() {
        return type;
    }
}

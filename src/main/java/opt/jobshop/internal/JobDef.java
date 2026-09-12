package opt.jobshop.internal;

import java.util.List;

/**
 * Internal definition of a job: its identifier, ordered operations, and weight.
 *
 * @param id         unique identifier of this job
 * @param operations ordered operations that make up this job
 * @param weight     relative importance (default 1); multiplies tardiness and completion-time terms
 */
public record JobDef(String id, List<OperationDef> operations, long weight) {
    public JobDef {
        operations = List.copyOf(operations);
    }
}

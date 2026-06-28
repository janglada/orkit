package opt.jobshop.internal;

import java.util.List;

/** Internal definition of a job: its identifier and ordered list of operations. */
public record JobDef(
        /** Unique identifier of this job. */
        String id,
        /** Ordered operations that make up this job. */
        List<OperationDef> operations
) {}

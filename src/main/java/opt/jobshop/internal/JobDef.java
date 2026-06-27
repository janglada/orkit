package opt.jobshop.internal;

import java.util.List;

public record JobDef(String id, List<OperationDef> operations) {}

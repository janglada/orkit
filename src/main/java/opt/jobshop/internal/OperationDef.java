package opt.jobshop.internal;

import java.util.List;

public record OperationDef(
        int index,
        /** Single machine, or multiple candidates for flexible job shop. */
        List<String> machines,
        int duration
) {
    public boolean isFlexible() {
        return machines.size() > 1;
    }

    public String machine() {
        if (isFlexible()) throw new IllegalStateException("Flexible operation; use machines().");
        return machines.get(0);
    }
}

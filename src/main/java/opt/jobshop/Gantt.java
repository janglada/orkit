package opt.jobshop;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Structured Gantt chart data ready to pass to a renderer. */
public final class Gantt {

    /** All scheduled operations, grouped by machine. */
    private final Map<String, List<ScheduledOperation>> byMachine;

    /** Ordered list of machine names. */
    private final List<String> machines;

    /** Constructs a Gantt chart from the ordered machine list and their per-machine timelines. */
    public Gantt(List<String> machines, Map<String, List<ScheduledOperation>> byMachine) {
        this.machines = Collections.unmodifiableList(machines);
        this.byMachine = Collections.unmodifiableMap(byMachine);
    }

    /** Returns the machines in display order. */
    public List<String> machines() {
        return machines;
    }

    /** Returns the operations scheduled on the given machine, or an empty list if unknown. */
    public List<ScheduledOperation> rowFor(String machine) {
        return byMachine.getOrDefault(machine, List.of());
    }
}

package opt.jobshop;

import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** The solved result of a job shop problem. */
public final class JobShopSchedule implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final long makespan;
    private final List<String> machines;
    private final List<ScheduledOperation> operations;

    public JobShopSchedule(SolveStatus status, OptionalDouble objective, Duration runtime,
                           long makespan, List<String> machines, List<ScheduledOperation> operations) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.makespan = makespan;
        this.machines = Collections.unmodifiableList(machines);
        this.operations = Collections.unmodifiableList(operations);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }

    public long makespan() {
        return makespan;
    }

    /** Returns all operations scheduled on the given machine, ordered by start time. */
    public List<ScheduledOperation> timelineOf(String machine) {
        return operations.stream()
                .filter(op -> op.machine().equals(machine))
                .sorted(Comparator.comparingLong(ScheduledOperation::start))
                .collect(Collectors.toList());
    }

    public List<ScheduledOperation> operationsOf(String job) {
        return operations.stream()
                .filter(op -> op.job().equals(job))
                .sorted(Comparator.comparingInt(ScheduledOperation::index))
                .collect(Collectors.toList());
    }

    public Gantt asGantt() {
        Map<String, List<ScheduledOperation>> byMachine = new LinkedHashMap<>();
        for (String m : machines) {
            byMachine.put(m, timelineOf(m));
        }
        return new Gantt(machines, byMachine);
    }
}

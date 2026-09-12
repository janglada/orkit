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
    private final Map<String, Long> tardinessByJob;

    /**
     * Constructs a solved schedule. Normally not called directly by users;
     * instances are produced by {@link opt.Solver#solve()}.
     */
    public JobShopSchedule(SolveStatus status, OptionalDouble objective, Duration runtime,
                           long makespan, List<String> machines, List<ScheduledOperation> operations,
                           Map<String, Long> tardinessByJob) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.makespan = makespan;
        this.machines = Collections.unmodifiableList(machines);
        this.operations = Collections.unmodifiableList(operations);
        this.tardinessByJob = Collections.unmodifiableMap(new LinkedHashMap<>(tardinessByJob));
    }

    /** Returns the solver status for this solution. */
    @Override public SolveStatus status()       { return status; }
    /** Returns the objective value (makespan) if a feasible solution was found. */
    @Override public OptionalDouble objective() { return objective; }
    /** Returns the wall-clock time spent solving. */
    @Override public Duration runtime()         { return runtime; }

    /** Returns the minimized makespan (time at which the last operation ends). */
    public long makespan() {
        return makespan;
    }

    /** Returns every scheduled operation, in job declaration order then operation index. */
    public List<ScheduledOperation> operations() {
        return operations;
    }

    /** Returns all operations scheduled on the given machine, ordered by start time. */
    public List<ScheduledOperation> timelineOf(String machine) {
        return operations.stream()
                .filter(op -> op.machine().equals(machine))
                .sorted(Comparator.comparingLong(ScheduledOperation::start))
                .collect(Collectors.toList());
    }

    /** Returns all operations belonging to the given job, ordered by their index in the job. */
    public List<ScheduledOperation> operationsOf(String job) {
        return operations.stream()
                .filter(op -> op.job().equals(job))
                .sorted(Comparator.comparingInt(ScheduledOperation::index))
                .collect(Collectors.toList());
    }

    /**
     * Sum of {@code max(0, end - due)} over this job's operations that carry a due date, in
     * model time units; {@code 0} for a job without due dates, an unknown job, or no solution.
     * Reported regardless of the goal.
     */
    public long tardinessOf(String job) {
        return tardinessByJob.getOrDefault(job, 0L);
    }

    /** Sum of {@link #tardinessOf(String)} over all jobs (unweighted). */
    public long totalTardiness() {
        return tardinessByJob.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Builds a {@link Gantt} view of this schedule grouped by machine. */
    public Gantt asGantt() {
        Map<String, List<ScheduledOperation>> byMachine = new LinkedHashMap<>();
        for (String m : machines) {
            byMachine.put(m, timelineOf(m));
        }
        return new Gantt(machines, byMachine);
    }
}

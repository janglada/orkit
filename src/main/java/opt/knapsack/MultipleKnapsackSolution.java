package opt.knapsack;

import opt.Solution;
import opt.SolveStatus;

import java.time.Duration;
import java.util.*;

/** The solved result of a multiple-knapsack problem. */
public final class MultipleKnapsackSolution implements Solution {

    private final SolveStatus status;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final long totalProfit;
    private final List<BinAssignment> assignments;
    private final List<String> unassignedItems;

    public MultipleKnapsackSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                                    long totalProfit, List<BinAssignment> assignments,
                                    List<String> unassignedItems) {
        this.status = status;
        this.objective = objective;
        this.runtime = runtime;
        this.totalProfit = totalProfit;
        this.assignments = Collections.unmodifiableList(assignments);
        this.unassignedItems = Collections.unmodifiableList(unassignedItems);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }

    /** Total profit of all packed items. Zero if no solution was found. */
    public long totalProfit() {
        return totalProfit;
    }

    /** One {@link BinAssignment} per declared bin, in declaration order (empty bins included). */
    public List<BinAssignment> assignments() {
        return assignments;
    }

    /** Item identifiers that were not packed into any bin. */
    public List<String> unassignedItems() {
        return unassignedItems;
    }

    /** The assignment for the given bin id; empty for an unknown bin or when no solution was found. */
    public Optional<BinAssignment> assignmentOf(String bin) {
        return assignments.stream().filter(a -> a.bin().equals(bin)).findFirst();
    }

    /** The bin id holding the given item; empty when the item is unassigned or unknown. */
    public Optional<String> binOf(String item) {
        for (BinAssignment a : assignments) {
            if (a.items().contains(item)) return Optional.of(a.bin());
        }
        return Optional.empty();
    }
}

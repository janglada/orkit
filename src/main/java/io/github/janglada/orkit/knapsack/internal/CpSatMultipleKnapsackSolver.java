package io.github.janglada.orkit.knapsack.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.internal.CpSatParams;
import java.util.function.Consumer;
import io.github.janglada.orkit.knapsack.BinAssignment;
import io.github.janglada.orkit.knapsack.KnapsackGoal;
import io.github.janglada.orkit.knapsack.MultipleKnapsackSolution;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a multiple-knapsack model into CP-SAT and solves it.
 *
 * <p>One boolean variable {@code x[i][b]} per (item, bin) indicates whether item
 * {@code i} is packed into bin {@code b}. Each item is constrained to appear in at
 * most one bin; each bin's total weight is constrained to its capacity; the total
 * profit of packed items is maximized.
 */
public final class CpSatMultipleKnapsackSolver {

    private final List<ItemDef> items;
    private final List<BinDef> bins;
    private final KnapsackGoal goal;
    private final CpSatParams params;
    private final Consumer<MultipleKnapsackSolution> listener;
    private final MultipleKnapsackSolution warmStart;


    /** Creates a solver for the given items, bins, and goal. Internal; obtain via {@link io.github.janglada.orkit.knapsack.MultipleKnapsackBuilder#solver()}. */
    public CpSatMultipleKnapsackSolver(List<ItemDef> items, List<BinDef> bins, KnapsackGoal goal,
                                       CpSatParams params, Consumer<MultipleKnapsackSolution> listener,
                                       MultipleKnapsackSolution warmStart) {
        this.items = List.copyOf(items);
        this.bins = List.copyOf(bins);
        this.goal = (goal == null) ? new KnapsackGoal.MaximizeTotalProfit() : goal;
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }


    /**
     * Builds the CP-SAT model, solves it, and maps the result back to bin/item
     * identifiers. Returns one {@link BinAssignment} per declared bin (empty
     * bins included, in declaration order) and lists items that were not packed
     * into any bin. If the solver finds no solution, both lists are empty.
     */
    public MultipleKnapsackSolution solve() {
        validate();

        int I = items.size();
        int B = bins.size();

        CpModel model = new CpModel();

        // x[i][b] = 1 iff item i is packed into bin b
        BoolVar[][] x = new BoolVar[I][B];
        for (int i = 0; i < I; i++) {
            for (int b = 0; b < B; b++) {
                x[i][b] = model.newBoolVar("x_" + items.get(i).id() + "_" + bins.get(b).id());
            }
        }

        // Each item is in at most one bin
        for (int i = 0; i < I; i++) {
            LinearExprBuilder sum = LinearExpr.newBuilder();
            for (int b = 0; b < B; b++) {
                sum.add(x[i][b]);
            }
            model.addLessOrEqual(sum, 1);
        }

        // Each bin's total weight must not exceed its capacity
        for (int b = 0; b < B; b++) {
            BoolVar[] vars = new BoolVar[I];
            long[] weights = new long[I];
            for (int i = 0; i < I; i++) {
                vars[i] = x[i][b];
                weights[i] = items.get(i).weight();
            }
            model.addLessOrEqual(buildWeightedSum(vars, weights), bins.get(b).capacity());
        }

        // Objective: maximize total profit of packed items
        LinearExprBuilder obj = LinearExpr.newBuilder();
        for (int i = 0; i < I; i++) {
            long profit = items.get(i).profit();
            for (int b = 0; b < B; b++) {
                obj.addTerm(x[i][b], profit);
            }
        }
        switch (goal) {
            case KnapsackGoal.MaximizeTotalProfit g -> model.maximize(obj);
        }

        if (warmStart != null) applyWarmStart(model, x);

        CpSatParams.Extractor<MultipleKnapsackSolution> extract = (v, status, runtime) -> {
            long totalProfit = 0;
            List<BinAssignment> assignments = new ArrayList<>();
            List<String> unassignedItems = new ArrayList<>();

            if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
                // Group assigned items by bin
                Map<String, List<String>> itemsByBin = new LinkedHashMap<>();
                for (String binId : bins.stream().map(BinDef::id).toList()) {
                    itemsByBin.put(binId, new ArrayList<>());
                }
                for (int i = 0; i < I; i++) {
                    String assignedBin = null;
                    for (int b = 0; b < B; b++) {
                        if (v.booleanValue(x[i][b])) {
                            assignedBin = bins.get(b).id();
                            break;
                        }
                    }
                    if (assignedBin != null) {
                        itemsByBin.get(assignedBin).add(items.get(i).id());
                    } else {
                        unassignedItems.add(items.get(i).id());
                    }
                }

                // Build a BinAssignment per bin (including empty bins, to mirror declared bins)
                for (BinDef bin : bins) {
                    List<String> packed = itemsByBin.get(bin.id());
                    long usedCapacity = 0;
                    long profit = 0;
                    for (String itemId : packed) {
                        ItemDef item = itemById(itemId);
                        usedCapacity += item.weight();
                        profit += item.profit();
                    }
                    assignments.add(new BinAssignment(bin.id(), bin.capacity(), List.copyOf(packed), usedCapacity, profit));
                    totalProfit += profit;
                }
            }

            OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                    ? OptionalDouble.of(totalProfit)
                    : OptionalDouble.empty();

            return new MultipleKnapsackSolution(status, objValue, runtime, totalProfit, assignments, unassignedItems);
        };
        return params.solve(model, extract, listener);
    }

    /** Hints every (item, bin) literal from a previous solution. */
    private void applyWarmStart(CpModel model, BoolVar[][] x) {
        for (int i = 0; i < items.size(); i++) {
            String prevBin = warmStart.binOf(items.get(i).id()).orElse(null);
            for (int b = 0; b < bins.size(); b++) {
                model.addHint(x[i][b], bins.get(b).id().equals(prevBin) ? 1 : 0);
            }
        }
    }

    private ItemDef itemById(String id) {
        for (ItemDef item : items) {
            if (item.id().equals(id)) return item;
        }
        throw new IllegalStateException("Unknown item id: " + id);
    }

    private void validate() {
        if (items.isEmpty()) throw new ModelException("At least one item is required.");
        if (bins.isEmpty()) throw new ModelException("At least one bin is required.");
    }



    /** Builds a weighted-sum LinearExpr from BoolVars and their coefficients. */
    private static LinearExpr buildWeightedSum(BoolVar[] vars, long[] coefficients) {
        LinearExprBuilder builder = LinearExpr.newBuilder();
        for (int i = 0; i < vars.length; i++) {
            builder.addTerm(vars[i], coefficients[i]);
        }
        return builder.build();
    }
}

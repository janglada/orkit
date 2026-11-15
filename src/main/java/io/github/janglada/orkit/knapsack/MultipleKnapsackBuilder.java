package io.github.janglada.orkit.knapsack;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.CpSatProblem;
import io.github.janglada.orkit.CpSatSolver;
import io.github.janglada.orkit.internal.AbstractCpSatSolver;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.knapsack.internal.BinDef;
import io.github.janglada.orkit.knapsack.internal.CpSatMultipleKnapsackSolver;
import io.github.janglada.orkit.knapsack.internal.ItemDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for multiple-knapsack problems. Entry point: {@code Optimize.multipleKnapsack()}. */
public final class MultipleKnapsackBuilder implements CpSatProblem<MultipleKnapsackSolution> {

    private final List<ItemDef> items = new ArrayList<>();
    private final List<BinDef> bins = new ArrayList<>();
    private final Set<String> itemIds = new HashSet<>();
    private final Set<String> binIds = new HashSet<>();
    private KnapsackGoal goal = Goals.maximizeProfit();

    /** Declares a knapsack (bin) with the given unique id and configures it via the {@link BinConfig} lambda. */
    public MultipleKnapsackBuilder bin(String id, Consumer<BinConfig> config) {
        Validate.nonBlank(id, "bin id");
        if (!binIds.add(id)) {
            throw new ModelException("Duplicate bin id: '" + id + "'.");
        }
        BinConfig cfg = new BinConfig();
        config.accept(cfg);
        if (cfg.capacity() < 1) {
            throw new ModelException("Bin '" + id + "' must set a capacity via .capacity(...).");
        }
        bins.add(new BinDef(id, cfg.capacity()));
        return this;
    }

    /** Declares an item with the given unique id and configures it via the {@link ItemConfig} lambda. */
    public MultipleKnapsackBuilder item(String id, Consumer<ItemConfig> config) {
        Validate.nonBlank(id, "item id");
        if (!itemIds.add(id)) {
            throw new ModelException("Duplicate item id: '" + id + "'.");
        }
        ItemConfig cfg = new ItemConfig();
        config.accept(cfg);
        if (cfg.profit() < 1) {
            throw new ModelException("Item '" + id + "' must set a profit via .profit(...).");
        }
        if (cfg.weight() < 1) {
            throw new ModelException("Item '" + id + "' must set a weight via .weight(...).");
        }
        items.add(new ItemDef(id, cfg.profit(), cfg.weight()));
        return this;
    }

    /** Sets the optimization goal. Defaults to {@link Goals#maximizeProfit()}. */
    public MultipleKnapsackBuilder goal(KnapsackGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.goal = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public CpSatProblem<MultipleKnapsackSolution> build() {
        return new MultipleKnapsackProblem(List.copyOf(items), List.copyOf(bins), goal);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<MultipleKnapsackSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class MultipleKnapsackProblem implements CpSatProblem<MultipleKnapsackSolution> {

        private final List<ItemDef> items;
        private final List<BinDef> bins;
        private final KnapsackGoal goal;

        MultipleKnapsackProblem(List<ItemDef> items, List<BinDef> bins, KnapsackGoal goal) {
            this.items = items;
            this.bins = bins;
            this.goal = goal;
        }

        @Override
        public CpSatSolver<MultipleKnapsackSolution> solver() {
            return new MultipleKnapsackSolver(items, bins, goal);
        }
    }

    private static final class MultipleKnapsackSolver extends AbstractCpSatSolver<MultipleKnapsackSolution> {

        private final List<ItemDef> items;
        private final List<BinDef> bins;
        private final KnapsackGoal goal;

        MultipleKnapsackSolver(List<ItemDef> items, List<BinDef> bins, KnapsackGoal goal) {
            this.items = items;
            this.bins = bins;
            this.goal = goal;
        }

        @Override
        protected MultipleKnapsackSolution doSolve() {
            return new CpSatMultipleKnapsackSolver(items, bins, goal, params, listener, warmStart).solve();
        }
    }
}

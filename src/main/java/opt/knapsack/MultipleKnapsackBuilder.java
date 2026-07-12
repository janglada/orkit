package opt.knapsack;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.internal.Validate;
import opt.knapsack.internal.BinDef;
import opt.knapsack.internal.CpSatMultipleKnapsackSolver;
import opt.knapsack.internal.ItemDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for multiple-knapsack problems. Entry point: {@code Optimize.multipleKnapsack()}. */
public final class MultipleKnapsackBuilder implements OptimizationProblem<MultipleKnapsackSolution> {

    private final List<ItemDef> items = new ArrayList<>();
    private final List<BinDef> bins = new ArrayList<>();
    private final Set<String> itemIds = new HashSet<>();
    private final Set<String> binIds = new HashSet<>();

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

    /** Builds an immutable, reusable problem instance (maximizes total profit). */
    public OptimizationProblem<MultipleKnapsackSolution> build() {
        return new MultipleKnapsackProblem(List.copyOf(items), List.copyOf(bins));
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public Solver<MultipleKnapsackSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class MultipleKnapsackProblem implements OptimizationProblem<MultipleKnapsackSolution> {

        private final List<ItemDef> items;
        private final List<BinDef> bins;

        MultipleKnapsackProblem(List<ItemDef> items, List<BinDef> bins) {
            this.items = items;
            this.bins = bins;
        }

        @Override
        public Solver<MultipleKnapsackSolution> solver() {
            return new MultipleKnapsackSolver(items, bins);
        }
    }

    private static final class MultipleKnapsackSolver implements Solver<MultipleKnapsackSolution> {

        private final List<ItemDef> items;
        private final List<BinDef> bins;
        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        MultipleKnapsackSolver(List<ItemDef> items, List<BinDef> bins) {
            this.items = items;
            this.bins = bins;
        }

        @Override public Solver<MultipleKnapsackSolution> timeLimit(Duration limit) { timeLimit = limit; return this; }
        @Override public Solver<MultipleKnapsackSolution> workers(int threads)       { workers = threads;  return this; }
        @Override public Solver<MultipleKnapsackSolution> seed(long randomSeed)      { seed = randomSeed;  return this; }
        @Override public Solver<MultipleKnapsackSolution> verbose(boolean log)       { verbose = log;      return this; }

        @Override
        public MultipleKnapsackSolution solve() {
            return new CpSatMultipleKnapsackSolver(items, bins)
                    .timeLimit(timeLimit)
                    .workers(workers)
                    .seed(seed)
                    .verbose(verbose)
                    .solve();
        }
    }
}

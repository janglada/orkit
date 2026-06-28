package opt.packing;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.packing.internal.BoxDef;
import opt.packing.internal.ContainerDef;
import opt.packing.internal.CpSatPackingSolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for container-loading / 3D bin packing problems. Entry point: {@code Optimize.packing()}. */
public final class PackingBuilder implements OptimizationProblem<PackingSolution> {

    private final List<ContainerDef> containers = new ArrayList<>();
    private final List<BoxDef> boxes = new ArrayList<>();
    private final Set<String> containerIds = new HashSet<>();
    private final Set<String> boxIds = new HashSet<>();
    private PackingGoal goal = new PackingGoal.MinimizeContainersUsed();

    /** Declares a container with the given unique id and configures it via the {@link ContainerConfig} lambda. */
    public PackingBuilder container(String id, Consumer<ContainerConfig> config) {
        if (!containerIds.add(id)) {
            throw new ModelException("Duplicate container id: '" + id + "'.");
        }
        ContainerConfig cfg = new ContainerConfig();
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Container '" + id + "' must set a length via .length(...).");
        }
        if (cfg.width() < 1) {
            throw new ModelException("Container '" + id + "' must set a width via .width(...).");
        }
        if (cfg.height() < 1) {
            throw new ModelException("Container '" + id + "' must set a height via .height(...).");
        }
        if (cfg.maxWeight() < 1) {
            throw new ModelException("Container '" + id + "' must set a maxWeight via .maxWeight(...).");
        }
        containers.add(new ContainerDef(id, cfg.length(), cfg.width(), cfg.height(), cfg.maxWeight()));
        return this;
    }

    /** Declares a box with the given unique id and configures it via the {@link BoxConfig} lambda. */
    public PackingBuilder box(String id, Consumer<BoxConfig> config) {
        if (!boxIds.add(id)) {
            throw new ModelException("Duplicate box id: '" + id + "'.");
        }
        BoxConfig cfg = new BoxConfig();
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Box '" + id + "' must set a length via .length(...).");
        }
        if (cfg.width() < 1) {
            throw new ModelException("Box '" + id + "' must set a width via .width(...).");
        }
        if (cfg.height() < 1) {
            throw new ModelException("Box '" + id + "' must set a height via .height(...).");
        }
        if (cfg.weight() < 1) {
            throw new ModelException("Box '" + id + "' must set a weight via .weight(...).");
        }
        boxes.add(new BoxDef(id, cfg.length(), cfg.width(), cfg.height(), cfg.weight()));
        return this;
    }

    /** Sets the optimization goal. Defaults to minimizing the number of containers used. */
    public PackingBuilder goal(PackingGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.goal = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public OptimizationProblem<PackingSolution> build() {
        return new PackingProblem(List.copyOf(containers), List.copyOf(boxes), goal);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public Solver<PackingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class PackingProblem implements OptimizationProblem<PackingSolution> {

        private final List<ContainerDef> containers;
        private final List<BoxDef> boxes;
        private final PackingGoal goal;

        PackingProblem(List<ContainerDef> containers, List<BoxDef> boxes, PackingGoal goal) {
            this.containers = containers;
            this.boxes = boxes;
            this.goal = goal;
        }

        @Override
        public Solver<PackingSolution> solver() {
            return new PackingSolver(containers, boxes, goal);
        }
    }

    private static final class PackingSolver implements Solver<PackingSolution> {

        private final List<ContainerDef> containers;
        private final List<BoxDef> boxes;
        private final PackingGoal goal;
        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        PackingSolver(List<ContainerDef> containers, List<BoxDef> boxes, PackingGoal goal) {
            this.containers = containers;
            this.boxes = boxes;
            this.goal = goal;
        }

        @Override public Solver<PackingSolution> timeLimit(Duration limit) { timeLimit = limit; return this; }
        @Override public Solver<PackingSolution> workers(int threads)       { workers = threads;  return this; }
        @Override public Solver<PackingSolution> seed(long randomSeed)      { seed = randomSeed;  return this; }
        @Override public Solver<PackingSolution> verbose(boolean log)       { verbose = log;      return this; }

        @Override
        public PackingSolution solve() {
            return new CpSatPackingSolver(containers, boxes, goal)
                    .timeLimit(timeLimit)
                    .workers(workers)
                    .seed(seed)
                    .verbose(verbose)
                    .solve();
        }
    }
}

package io.github.janglada.orkit.packing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.CpSatProblem;
import io.github.janglada.orkit.CpSatSolver;
import io.github.janglada.orkit.internal.AbstractCpSatSolver;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.packing.internal.BoxDef;
import io.github.janglada.orkit.packing.internal.ContainerDef;
import io.github.janglada.orkit.packing.internal.CpSatPackingSolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for container-loading / 3D bin packing problems. Entry point: {@code Optimize.packing()}. */
public final class PackingBuilder implements CpSatProblem<PackingSolution> {

    private final List<ContainerDef> containers = new ArrayList<>();
    private final List<BoxDef> boxes = new ArrayList<>();
    private final Set<String> containerIds = new HashSet<>();
    private final Set<String> boxIds = new HashSet<>();
    private PackingGoal goal = new PackingGoal.MinimizeContainersUsed();
    private boolean requireSupport = false;

    /** Declares a container with the given unique id and configures it via the {@link ContainerConfig} lambda. */
    public PackingBuilder container(String id, Consumer<ContainerConfig> config) {
        Validate.nonBlank(id, "container id");
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
        Validate.nonBlank(id, "box id");
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
        boxes.add(new BoxDef(id, cfg.length(), cfg.width(), cfg.height(), cfg.weight(),
                cfg.rotatable(), cfg.maxStackedWeight(),
                List.copyOf(cfg.aboveIds()), List.copyOf(cfg.belowIds())));
        return this;
    }

    /** Sets the optimization goal. Defaults to minimizing the number of containers used. */
    public PackingBuilder goal(PackingGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.goal = goal;
        return this;
    }

    /**
     * Toggles physical support. When enabled, every packed box stands on the container floor
     * or rests directly on one other box: its bottom touches that box's top and its XY
     * footprint lies inside that box's footprint, so no box hovers. Disabled by default,
     * which keeps the classical no-overlap-only model where boxes may float.
     */
    public PackingBuilder requireSupport(boolean requireSupport) {
        this.requireSupport = requireSupport;
        return this;
    }

    /** Builds an immutable, reusable problem instance. */
    public CpSatProblem<PackingSolution> build() {
        return new PackingProblem(List.copyOf(containers), List.copyOf(boxes), goal, requireSupport);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<PackingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class PackingProblem implements CpSatProblem<PackingSolution> {

        private final List<ContainerDef> containers;
        private final List<BoxDef> boxes;
        private final PackingGoal goal;
        private final boolean requireSupport;

        PackingProblem(List<ContainerDef> containers, List<BoxDef> boxes,
                       PackingGoal goal, boolean requireSupport) {
            this.containers = containers;
            this.boxes = boxes;
            this.goal = goal;
            this.requireSupport = requireSupport;
        }

        @Override
        public CpSatSolver<PackingSolution> solver() {
            return new PackingSolver(containers, boxes, goal, requireSupport);
        }
    }

    private static final class PackingSolver extends AbstractCpSatSolver<PackingSolution> {

        private final List<ContainerDef> containers;
        private final List<BoxDef> boxes;
        private final PackingGoal goal;
        private final boolean requireSupport;

        PackingSolver(List<ContainerDef> containers, List<BoxDef> boxes,
                      PackingGoal goal, boolean requireSupport) {
            this.containers = containers;
            this.boxes = boxes;
            this.goal = goal;
            this.requireSupport = requireSupport;
        }

        @Override
        protected PackingSolution doSolve() {
            return new CpSatPackingSolver(containers, boxes, goal, requireSupport, params, listener, warmStart).solve();
        }
    }
}

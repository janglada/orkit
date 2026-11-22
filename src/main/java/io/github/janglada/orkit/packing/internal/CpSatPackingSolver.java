package io.github.janglada.orkit.packing.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.internal.ConstraintGroups;
import io.github.janglada.orkit.internal.CpSatParams;
import io.github.janglada.orkit.internal.CpValues;
import io.github.janglada.orkit.internal.Validate;
import io.github.janglada.orkit.packing.ContainerAssignment;
import io.github.janglada.orkit.packing.Orientation;
import io.github.janglada.orkit.packing.PackingGoal;
import io.github.janglada.orkit.packing.PackingSolution;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Compiles a 3D bin-packing / container-loading model into CP-SAT and solves it.
 *
 * <p>Each box has one boolean {@code x[i][b]} per container it fits in, and one position
 * {@code (sx, sy, sz)} and size per axis shared by every container: a box sits in at most one
 * container, so its coordinates are read in that container's frame. Every pair of boxes gets
 * six separation literals (before/after on X, Y and Z), and at least one must hold when both
 * boxes share a container. Redundant constraints strengthen the search: per-container volume,
 * a per-axis {@code Cumulative} on cross-sections, and symmetry breaking between identical
 * containers and identical boxes.
 *
 * <p>Optional sub-features (all off by default, preserving the classical model):
 * <ul>
 *   <li><b>Rotation</b> — a rotatable box takes exactly one distinct axis-permutation of its
 *       dimensions, which must fit the container it is placed in.</li>
 *   <li><b>Support</b> — with {@code requireSupport}, every placed box stands on the floor or
 *       rests directly on one box: its bottom touches that box's top and its footprint lies
 *       inside that box's footprint.</li>
 *   <li><b>Stacking precedence</b> — {@code above(id)}/{@code below(id)} force a vertical
 *       order between two boxes sharing a container.</li>
 *   <li><b>Weight-bearing</b> — {@code maxStackedWeight} caps the total weight of the boxes
 *       placed above a box, separated from it vertically.</li>
 * </ul>
 *
 * <p>Objectives: {@link PackingGoal.MinimizeContainersUsed} packs every box that fits
 * somewhere and minimizes non-empty containers; {@link PackingGoal.MaximizeVolumeUtilization}
 * maximizes packed volume and may leave boxes out.
 */
public final class CpSatPackingSolver {

    private static final int X_BEFORE = 0, X_AFTER = 1, Y_BEFORE = 2, Y_AFTER = 3, Z_BELOW = 4, Z_ABOVE = 5;

    private final List<BoxDef> boxes;
    private final List<ContainerDef> containers;
    private final PackingGoal goal;
    private final boolean requireSupport;
    private final CpSatParams params;
    private final Consumer<PackingSolution> listener;
    private final PackingSolution warmStart;

    /** Creates a solver for the given containers, boxes, goal, and options. Internal; obtain via {@link io.github.janglada.orkit.packing.PackingBuilder#solver()}. */
    public CpSatPackingSolver(List<ContainerDef> containers, List<BoxDef> boxes,
                              PackingGoal goal, boolean requireSupport,
                              CpSatParams params, Consumer<PackingSolution> listener, PackingSolution warmStart) {
        this.containers = List.copyOf(containers);
        this.boxes = List.copyOf(boxes);
        this.goal = goal;
        this.requireSupport = requireSupport;
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }

    /**
     * Builds the CP-SAT model, solves it, and maps the result back to container/box
     * identifiers. Returns one {@link ContainerAssignment} per declared container (empty
     * containers included, in declaration order) and lists boxes that were not placed
     * into any container. If the solver finds no solution, both lists are empty.
     */
    public PackingSolution solve() {
        validate();
        CpModel model = new CpModel();
        ConstraintGroups groups = params.groups(model);
        Vars v = new Vars(model);
        Constraints.placement(this, model, v, groups);
        Constraints.redundant(this, model, v);
        Constraints.symmetry(this, model, v);
        Stacking.precedence(this, model, v);
        Stacking.weightBearing(this, model, v);
        if (requireSupport) Stacking.support(this, model, v);
        objective(model, v);
        if (warmStart != null) hint(model, v);
        return params.solve(model, (values, status, runtime, diag) -> extract(v, values, status, runtime, diag), listener);
    }

    // ── Variables ────────────────────────────────────────────────────────────

    /** Every variable of the model, shared by the constraint groups and the extraction. */
    private final class Vars {
        final int numBoxes = boxes.size();
        final int numContainers = containers.size();
        final BoolVar[][] x = new BoolVar[numBoxes][numContainers];   // null where box i never fits container b
        final LinearExpr[] placed = new LinearExpr[numBoxes];
        final BoolVar[] used = new BoolVar[numContainers];
        final IntVar[] sx = new IntVar[numBoxes], sy = new IntVar[numBoxes], sz = new IntVar[numBoxes];
        final IntVar[] ex = new IntVar[numBoxes], ey = new IntVar[numBoxes], ez = new IntVar[numBoxes];
        final IntVar[] sizeX = new IntVar[numBoxes], sizeY = new IntVar[numBoxes], sizeZ = new IntVar[numBoxes];
        final List<List<Orientation>> orientations = new ArrayList<>();
        final BoolVar[][] orient = new BoolVar[numBoxes][];           // null for single-orientation boxes
        final BoolVar[][][] sep = new BoolVar[6][numBoxes][numBoxes];  // [kind][i][j], i < j; null when never together

        Vars(CpModel model) {
            long maxL = 0, maxW = 0, maxH = 0;
            for (ContainerDef c : containers) {
                maxL = Math.max(maxL, c.length());
                maxW = Math.max(maxW, c.width());
                maxH = Math.max(maxH, c.height());
            }
            for (int b = 0; b < numContainers; b++) used[b] = model.newBoolVar("used_" + containers.get(b).id());
            for (int i = 0; i < numBoxes; i++) addBox(model, i, maxL, maxW, maxH);
            for (int i = 0; i < numBoxes; i++) {
                for (int j = i + 1; j < numBoxes; j++) {
                    if (!canShare(i, j)) continue;
                    String tag = i + "_" + j;
                    for (int kind = 0; kind < 6; kind++) sep[kind][i][j] = model.newBoolVar("sep" + kind + "_" + tag);
                    model.addLessOrEqual(ex[i], sx[j]).onlyEnforceIf(sep[X_BEFORE][i][j]);
                    model.addLessOrEqual(ex[j], sx[i]).onlyEnforceIf(sep[X_AFTER][i][j]);
                    model.addLessOrEqual(ey[i], sy[j]).onlyEnforceIf(sep[Y_BEFORE][i][j]);
                    model.addLessOrEqual(ey[j], sy[i]).onlyEnforceIf(sep[Y_AFTER][i][j]);
                    model.addLessOrEqual(ez[i], sz[j]).onlyEnforceIf(sep[Z_BELOW][i][j]);
                    model.addLessOrEqual(ez[j], sz[i]).onlyEnforceIf(sep[Z_ABOVE][i][j]);
                }
            }
        }

        private void addBox(CpModel model, int i, long maxL, long maxW, long maxH) {
            BoxDef box = boxes.get(i);
            List<Orientation> os = box.orientations();
            orientations.add(os);
            LinearExprBuilder placedSum = LinearExpr.newBuilder();
            for (int b = 0; b < numContainers; b++) {
                ContainerDef c = containers.get(b);
                if (os.stream().noneMatch(o -> fits(o, c))) continue;
                x[i][b] = model.newBoolVar("x_" + box.id() + "_" + c.id());
                placedSum.add(x[i][b]);
            }
            placed[i] = placedSum.build();

            long minX = os.stream().mapToLong(Orientation::length).min().orElseThrow();
            long minY = os.stream().mapToLong(Orientation::width).min().orElseThrow();
            long minZ = os.stream().mapToLong(Orientation::height).min().orElseThrow();
            long maxX = os.stream().mapToLong(Orientation::length).max().orElseThrow();
            long maxY = os.stream().mapToLong(Orientation::width).max().orElseThrow();
            long maxZ = os.stream().mapToLong(Orientation::height).max().orElseThrow();
            sizeX[i] = model.newIntVar(minX, maxX, "szx_" + i);
            sizeY[i] = model.newIntVar(minY, maxY, "szy_" + i);
            sizeZ[i] = model.newIntVar(minZ, maxZ, "szz_" + i);
            if (os.size() > 1) {
                // Exactly one orientation; each axis size follows it. CP-SAT wants an interval
                // size to be a single variable, hence the reified equalities.
                orient[i] = new BoolVar[os.size()];
                for (int k = 0; k < os.size(); k++) {
                    orient[i][k] = model.newBoolVar("o_" + box.id() + "_" + k);
                    Orientation o = os.get(k);
                    model.addEquality(sizeX[i], o.length()).onlyEnforceIf(orient[i][k]);
                    model.addEquality(sizeY[i], o.width()).onlyEnforceIf(orient[i][k]);
                    model.addEquality(sizeZ[i], o.height()).onlyEnforceIf(orient[i][k]);
                    // An orientation that does not fit a container excludes that container.
                    for (int b = 0; b < numContainers; b++) {
                        if (x[i][b] != null && !fits(o, containers.get(b))) {
                            model.addBoolOr(new Literal[]{orient[i][k].not(), x[i][b].not()});
                        }
                    }
                }
                model.addExactlyOne(orient[i]);
            }

            sx[i] = model.newIntVar(0, Math.max(0, maxL - minX), "sx_" + i);
            sy[i] = model.newIntVar(0, Math.max(0, maxW - minY), "sy_" + i);
            sz[i] = model.newIntVar(0, Math.max(0, maxH - minZ), "sz_" + i);
            ex[i] = model.newIntVar(0, Math.max(maxL, maxX), "ex_" + i);
            ey[i] = model.newIntVar(0, Math.max(maxW, maxY), "ey_" + i);
            ez[i] = model.newIntVar(0, Math.max(maxH, maxZ), "ez_" + i);
            model.addEquality(ex[i], LinearExpr.newBuilder().add(sx[i]).add(sizeX[i]).build());
            model.addEquality(ey[i], LinearExpr.newBuilder().add(sy[i]).add(sizeY[i]).build());
            model.addEquality(ez[i], LinearExpr.newBuilder().add(sz[i]).add(sizeZ[i]).build());
            for (int b = 0; b < numContainers; b++) {
                if (x[i][b] == null) continue;
                ContainerDef c = containers.get(b);
                model.addLessOrEqual(ex[i], c.length()).onlyEnforceIf(x[i][b]);
                model.addLessOrEqual(ey[i], c.width()).onlyEnforceIf(x[i][b]);
                model.addLessOrEqual(ez[i], c.height()).onlyEnforceIf(x[i][b]);
            }
        }

        boolean canShare(int i, int j) {
            for (int b = 0; b < numContainers; b++) if (x[i][b] != null && x[j][b] != null) return true;
            return false;
        }

        /** The literal meaning "box j is above box i", or null if they never share a container. */
        BoolVar above(int j, int i) {
            return i < j ? sep[Z_BELOW][i][j] : sep[Z_ABOVE][j][i];
        }
    }

    private static boolean fits(Orientation o, ContainerDef c) {
        return o.length() <= c.length() && o.width() <= c.width() && o.height() <= c.height();
    }

    // ── Core and redundant constraints ──────────────────────────────────────

    private static final class Constraints {

        /** At most one container per box (exactly one under container minimization), no overlap, weight, used links. */
        static void placement(CpSatPackingSolver s, CpModel model, Vars v, ConstraintGroups groups) {
            boolean packAll = s.goal instanceof PackingGoal.MinimizeContainersUsed;
            for (int i = 0; i < v.numBoxes; i++) {
                // Container minimization must pack every box that fits somewhere, otherwise
                // packing nothing would be optimal.
                boolean fitsSomewhere = false;
                for (int b = 0; b < v.numContainers; b++) fitsSomewhere |= v.x[i][b] != null;
                if (packAll && fitsSomewhere) {
                    groups.guard(model.addEquality(v.placed[i], 1), "box '" + s.boxes.get(i).id() + "' must be packed");
                }
                else model.addLessOrEqual(v.placed[i], 1);
            }
            for (int b = 0; b < v.numContainers; b++) {
                LinearExprBuilder weight = LinearExpr.newBuilder();
                LinearExprBuilder count = LinearExpr.newBuilder();
                for (int i = 0; i < v.numBoxes; i++) {
                    if (v.x[i][b] == null) continue;
                    weight.addTerm(v.x[i][b], s.boxes.get(i).weight());
                    count.add(v.x[i][b]);
                    model.addLessOrEqual(v.x[i][b], v.used[b]);
                }
                ContainerDef c = s.containers.get(b);
                groups.guard(model.addLessOrEqual(weight, c.maxWeight()),
                        "weight limit " + c.maxWeight() + " of container '" + c.id() + "'");
                model.addLessOrEqual(v.used[b], count);
            }
            // Two boxes in one container are separated along at least one axis.
            for (int i = 0; i < v.numBoxes; i++) {
                for (int j = i + 1; j < v.numBoxes; j++) {
                    if (v.sep[0][i][j] == null) continue;
                    Literal[] any = new Literal[6];
                    for (int kind = 0; kind < 6; kind++) any[kind] = v.sep[kind][i][j];
                    for (int b = 0; b < v.numContainers; b++) {
                        if (v.x[i][b] == null || v.x[j][b] == null) continue;
                        model.addBoolOr(any).onlyEnforceIf(new Literal[]{v.x[i][b], v.x[j][b]});
                    }
                }
            }
        }

        /** Volume per container, and the cross-section of every slice along each axis. */
        static void redundant(CpSatPackingSolver s, CpModel model, Vars v) {
            for (int b = 0; b < v.numContainers; b++) {
                ContainerDef c = s.containers.get(b);
                String what = "Container '" + c.id() + "' volume";
                long volume = Validate.multiply(Validate.multiply(c.length(), c.width(), what), c.height(), what);
                LinearExprBuilder packed = LinearExpr.newBuilder();
                CumulativeConstraint alongX = model.addCumulative(c.width() * c.height());
                CumulativeConstraint alongY = model.addCumulative(c.length() * c.height());
                CumulativeConstraint alongZ = model.addCumulative(c.length() * c.width());
                for (int i = 0; i < v.numBoxes; i++) {
                    if (v.x[i][b] == null) continue;
                    packed.addTerm(v.x[i][b], s.boxes.get(i).volume());
                    List<Orientation> os = v.orientations.get(i);
                    String tag = i + "_" + b;
                    alongX.addDemand(model.newOptionalIntervalVar(v.sx[i], v.sizeX[i], v.ex[i], v.x[i][b], "cx_" + tag),
                            os.stream().mapToLong(o -> o.width() * o.height()).min().orElseThrow());
                    alongY.addDemand(model.newOptionalIntervalVar(v.sy[i], v.sizeY[i], v.ey[i], v.x[i][b], "cy_" + tag),
                            os.stream().mapToLong(o -> o.length() * o.height()).min().orElseThrow());
                    alongZ.addDemand(model.newOptionalIntervalVar(v.sz[i], v.sizeZ[i], v.ez[i], v.x[i][b], "cz_" + tag),
                            os.stream().mapToLong(o -> o.length() * o.width()).min().orElseThrow());
                }
                packed.addTerm(v.used[b], -volume);
                model.addLessOrEqual(packed, 0);
            }
        }

        /** Identical containers fill in order; identical free boxes take containers in order. */
        static void symmetry(CpSatPackingSolver s, CpModel model, Vars v) {
            for (int b = 1; b < v.numContainers; b++) {
                ContainerDef p = s.containers.get(b - 1), c = s.containers.get(b);
                if (p.length() == c.length() && p.width() == c.width() && p.height() == c.height()
                        && p.maxWeight() == c.maxWeight()) {
                    model.addGreaterOrEqual(v.used[b - 1], v.used[b]);
                }
            }
            Set<String> referenced = new HashSet<>();
            for (BoxDef box : s.boxes) {
                referenced.addAll(box.aboveIds());
                referenced.addAll(box.belowIds());
            }
            for (int i = 1; i < v.numBoxes; i++) {
                if (interchangeable(s.boxes.get(i - 1), s.boxes.get(i), referenced)) {
                    model.addLessOrEqual(containerIndex(v, i - 1), containerIndex(v, i));
                }
            }
        }

        /** Same size, weight and options, and no precedence declared on or against either box. */
        private static boolean interchangeable(BoxDef p, BoxDef c, Set<String> referenced) {
            return p.length() == c.length() && p.width() == c.width() && p.height() == c.height()
                    && p.weight() == c.weight() && p.rotatable() == c.rotatable()
                    && p.maxStackedWeight() == c.maxStackedWeight()
                    && p.aboveIds().isEmpty() && p.belowIds().isEmpty()
                    && c.aboveIds().isEmpty() && c.belowIds().isEmpty()
                    && !referenced.contains(p.id()) && !referenced.contains(c.id());
        }

        /** Index of the container holding box i, or the container count when it is left out. */
        private static LinearExpr containerIndex(Vars v, int i) {
            LinearExprBuilder index = LinearExpr.newBuilder().add(v.numContainers);
            for (int b = 0; b < v.numContainers; b++) {
                if (v.x[i][b] != null) index.addTerm(v.x[i][b], b - v.numContainers);
            }
            return index.build();
        }
    }

    // ── Stacking sub-features ───────────────────────────────────────────────

    private static final class Stacking {

        /** {@code above(id)} / {@code below(id)}: force the vertical order when both share a container. */
        static void precedence(CpSatPackingSolver s, CpModel model, Vars v) {
            for (int i = 0; i < v.numBoxes; i++) {
                BoxDef box = s.boxes.get(i);
                for (String otherId : box.aboveIds()) force(model, v, i, s.boxIndex(otherId));   // i above other
                for (String otherId : box.belowIds()) force(model, v, s.boxIndex(otherId), i);   // other above i
            }
        }

        private static void force(CpModel model, Vars v, int top, int bottom) {
            BoolVar lit = v.above(top, bottom);
            if (lit == null) return; // the two boxes can never share a container
            for (int b = 0; b < v.numContainers; b++) {
                if (v.x[top][b] == null || v.x[bottom][b] == null) continue;
                model.addBoolOr(new Literal[]{lit, v.x[top][b].not(), v.x[bottom][b].not()});
            }
        }

        /** {@code maxStackedWeight}: the boxes placed above a box weigh at most its cap. */
        static void weightBearing(CpSatPackingSolver s, CpModel model, Vars v) {
            for (int i = 0; i < v.numBoxes; i++) {
                long cap = s.boxes.get(i).maxStackedWeight();
                if (cap == Long.MAX_VALUE) continue;
                LinearExprBuilder loadSum = LinearExpr.newBuilder();
                for (int j = 0; j < v.numBoxes; j++) {
                    if (j == i) continue;
                    BoolVar jAboveI = v.above(j, i);
                    if (jAboveI != null) loadSum.addTerm(jAboveI, s.boxes.get(j).weight());
                }
                LinearExpr load = loadSum.build();
                for (int b = 0; b < v.numContainers; b++) {
                    if (v.x[i][b] != null) model.addLessOrEqual(load, cap).onlyEnforceIf(v.x[i][b]);
                }
            }
        }

        /**
         * {@code requireSupport}: a placed box stands on the floor or rests on exactly one box in
         * the same container, touching its top, with its footprint inside that box's footprint.
         */
        static void support(CpSatPackingSolver s, CpModel model, Vars v) {
            for (int i = 0; i < v.numBoxes; i++) {
                String id = s.boxes.get(i).id();
                BoolVar floor = model.newBoolVar("floor_" + id);
                model.addEquality(v.sz[i], 0).onlyEnforceIf(floor);
                LinearExprBuilder supports = LinearExpr.newBuilder().add(floor);
                for (int j = 0; j < v.numBoxes; j++) {
                    if (j == i || !v.canShare(i, j)) continue;
                    BoolVar on = model.newBoolVar("on_" + id + "_" + s.boxes.get(j).id());
                    supports.add(on);
                    // Resting on j means sharing j's container.
                    for (int b = 0; b < v.numContainers; b++) {
                        if (v.x[i][b] == null) continue;
                        if (v.x[j][b] == null) model.addBoolOr(new Literal[]{on.not(), v.x[i][b].not()});
                        else model.addBoolOr(new Literal[]{on.not(), v.x[i][b].not(), v.x[j][b]});
                    }
                    model.addEquality(v.sz[i], v.ez[j]).onlyEnforceIf(on);
                    model.addLessOrEqual(v.sx[j], v.sx[i]).onlyEnforceIf(on);
                    model.addLessOrEqual(v.ex[i], v.ex[j]).onlyEnforceIf(on);
                    model.addLessOrEqual(v.sy[j], v.sy[i]).onlyEnforceIf(on);
                    model.addLessOrEqual(v.ey[i], v.ey[j]).onlyEnforceIf(on);
                }
                model.addEquality(supports, v.placed[i]);
            }
        }
    }

    // ── Objective, warm start, extraction ───────────────────────────────────

    private void objective(CpModel model, Vars v) {
        LinearExprBuilder obj = LinearExpr.newBuilder();
        switch (goal) {
            case PackingGoal.MinimizeContainersUsed g -> {
                for (BoolVar u : v.used) obj.add(u);
                model.minimize(obj);
            }
            case PackingGoal.MaximizeVolumeUtilization g -> {
                for (int i = 0; i < v.numBoxes; i++) {
                    for (int b = 0; b < v.numContainers; b++) {
                        if (v.x[i][b] != null) obj.addTerm(v.x[i][b], boxes.get(i).volume());
                    }
                }
                model.maximize(obj);
            }
            case null, default -> throw new ModelException("Unsupported packing goal: " + goal);
        }
    }

    /** Hints container, origin, and orientation for every box placed in a previous solution. */
    private void hint(CpModel model, Vars v) {
        Map<String, Integer> boxIdx = new HashMap<>();
        for (int i = 0; i < v.numBoxes; i++) boxIdx.put(boxes.get(i).id(), i);
        Map<String, Integer> contIdx = new HashMap<>();
        for (int b = 0; b < v.numContainers; b++) contIdx.put(containers.get(b).id(), b);
        for (ContainerAssignment prev : warmStart.assignments()) {
            Integer b = contIdx.get(prev.containerId());
            if (b == null) continue;
            for (ContainerAssignment.BoxPlacement p : prev.boxes()) {
                Integer i = boxIdx.get(p.boxId());
                if (i == null || v.x[i][b] == null) continue;
                for (int bb = 0; bb < v.numContainers; bb++) {
                    if (v.x[i][bb] != null) model.addHint(v.x[i][bb], bb == b ? 1 : 0);
                }
                model.addHint(v.sx[i], p.x());
                model.addHint(v.sy[i], p.y());
                model.addHint(v.sz[i], p.z());
                if (v.orient[i] != null && p.orientation() != null) {
                    List<Orientation> os = v.orientations.get(i);
                    for (int k = 0; k < os.size(); k++) {
                        model.addHint(v.orient[i][k], os.get(k).equals(p.orientation()) ? 1 : 0);
                    }
                }
            }
        }
    }

    private PackingSolution extract(Vars v, CpValues values, SolveStatus status, Duration runtime, SolveDiagnostics diag) {
        boolean solved = status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;
        List<ContainerAssignment> assignments = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();
        if (solved) {
            List<List<ContainerAssignment.BoxPlacement>> byContainer = new ArrayList<>();
            long[] usedWeight = new long[v.numContainers];
            for (int b = 0; b < v.numContainers; b++) byContainer.add(new ArrayList<>());
            for (int i = 0; i < v.numBoxes; i++) {
                BoxDef box = boxes.get(i);
                int at = -1;
                for (int b = 0; b < v.numContainers && at < 0; b++) {
                    if (v.x[i][b] != null && values.booleanValue(v.x[i][b])) at = b;
                }
                if (at < 0) {
                    unassigned.add(box.id());
                    continue;
                }
                Orientation chosen = null;
                long pl = box.length(), pw = box.width(), ph = box.height();
                if (box.rotatable()) {
                    int k = 0;
                    if (v.orient[i] != null) {
                        while (!values.booleanValue(v.orient[i][k])) k++;
                    }
                    chosen = v.orientations.get(i).get(k);
                    pl = chosen.length();
                    pw = chosen.width();
                    ph = chosen.height();
                }
                byContainer.get(at).add(new ContainerAssignment.BoxPlacement(box.id(),
                        values.value(v.sx[i]), values.value(v.sy[i]), values.value(v.sz[i]), pl, pw, ph, chosen));
                usedWeight[at] += box.weight();
            }
            for (int b = 0; b < v.numContainers; b++) {
                ContainerDef c = containers.get(b);
                long usedVolume = 0;
                for (ContainerAssignment.BoxPlacement p : byContainer.get(b)) usedVolume += p.length() * p.width() * p.height();
                assignments.add(new ContainerAssignment(c.id(), c.length(), c.width(), c.height(), c.maxWeight(),
                        List.copyOf(byContainer.get(b)), usedVolume, usedWeight[b]));
            }
        }
        OptionalDouble objValue = solved ? OptionalDouble.of(values.objectiveValue()) : OptionalDouble.empty();
        return new PackingSolution(status, objValue, runtime, assignments, unassigned, diag);
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private int boxIndex(String id) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).id().equals(id)) return i;
        }
        throw new ModelException("Unknown box id referenced in above()/below(): '" + id + "'.");
    }

    private void validate() {
        if (containers.isEmpty()) throw new ModelException("At least one container is required.");
        if (boxes.isEmpty()) throw new ModelException("At least one box is required.");

        // Volumes and cross-sections feed coefficients and reported totals; reject inputs whose
        // products or sums would silently wrap around.
        long totalBoxVolume = 0;
        for (BoxDef box : boxes) totalBoxVolume = Validate.add(totalBoxVolume, box.volume(), "Total box volume");
        long totalContainerVolume = 0;
        for (ContainerDef c : containers) {
            String what = "Container '" + c.id() + "' volume";
            totalContainerVolume = Validate.add(totalContainerVolume,
                    Validate.multiply(Validate.multiply(c.length(), c.width(), what), c.height(), what),
                    "Total container volume");
        }

        // Resolve above()/below() ids and detect contradictory pairs: collect "u above w".
        Set<String> ids = new HashSet<>();
        for (BoxDef box : boxes) ids.add(box.id());
        Set<List<Integer>> abovePairs = new LinkedHashSet<>();
        for (int u = 0; u < boxes.size(); u++) {
            BoxDef box = boxes.get(u);
            for (String other : box.aboveIds()) {
                if (!ids.contains(other)) {
                    throw new ModelException("above('" + other + "') on box '" + box.id() + "' references an unknown box id.");
                }
                abovePairs.add(List.of(u, boxIndex(other)));
            }
            for (String other : box.belowIds()) {
                if (!ids.contains(other)) {
                    throw new ModelException("below('" + other + "') on box '" + box.id() + "' references an unknown box id.");
                }
                abovePairs.add(List.of(boxIndex(other), u));
            }
        }
        for (List<Integer> pair : abovePairs) {
            int u = pair.get(0), w = pair.get(1);
            if (u == w) {
                throw new ModelException("Box '" + boxes.get(u).id() + "' cannot be declared above/below itself.");
            }
            if (abovePairs.contains(List.of(w, u))) {
                throw new ModelException("Contradictory stacking precedence: '" + boxes.get(u).id() + " above "
                        + boxes.get(w).id() + "' and its reverse are both declared.");
            }
        }
    }
}

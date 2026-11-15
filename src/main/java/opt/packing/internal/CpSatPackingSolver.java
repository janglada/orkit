package opt.packing.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.internal.CpSatParams;
import opt.internal.Validate;
import java.util.function.Consumer;
import opt.packing.ContainerAssignment;
import opt.packing.Orientation;
import opt.packing.PackingGoal;
import opt.packing.PackingSolution;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a 3D bin-packing / container-loading model into CP-SAT and solves it.
 *
 * <p>One boolean variable {@code x[i][b]} per (box, container) indicates whether box
 * {@code i} is placed into container {@code b}. For each placement that physically fits
 * (box dimensions {@code <=} container dimensions on every axis, in some orientation when
 * rotation is enabled), three optional interval variables model the box's X/Y/Z footprint;
 * per-container no-overlap constraints prevent intersecting placements. A second boolean
 * {@code used[b]} tracks whether container {@code b} receives any box.
 *
 * <p>Optional sub-features (all off by default, preserving the classical model):
 * <ul>
 *   <li><b>Rotation</b> — a rotatable box may take any distinct axis-permutation of its
 *       dimensions; one orientation literal per admissible orientation enforces exactly
 *       one orientation when placed.</li>
 *   <li><b>Support footprint</b> — when {@code requireSupport} is on, a box resting on
 *       another must have its XY footprint contained within the supporting box.</li>
 *   <li><b>Stacking precedence</b> — {@code above(id)}/{@code below(id)} force a
 *       vertical ordering between two boxes via the no-overlap separation literals.</li>
 *   <li><b>Weight-bearing</b> — {@code maxStackedWeight} caps the total weight of boxes
 *       resting directly on a supporting box.</li>
 * </ul>
 *
 * <p>Objectives:
 * <ul>
 *   <li>{@link PackingGoal.MinimizeContainersUsed} — classic bin packing: every box that
 *       fits somewhere must be packed, and the number of non-empty containers is minimized.</li>
 *   <li>{@link PackingGoal.MaximizeVolumeUtilization} — maximize the total packed box
 *       volume (boxes may be left out when space or weight is tight).</li>
 * </ul>
 */
public final class CpSatPackingSolver {

    private final List<BoxDef> boxes;
    private final List<ContainerDef> containers;
    private final PackingGoal goal;
    private final boolean requireSupport;
    private final CpSatParams params;
    private final Consumer<PackingSolution> listener;
    private final PackingSolution warmStart;


    /** Creates a solver for the given containers, boxes, goal, and options. Internal; obtain via {@link opt.packing.PackingBuilder#solver()}. */
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

        int I = boxes.size();
        int B = containers.size();

        // ── Feature flags ──────────────────────────────────────────────────
        // When NONE of the opt-in features are used, the model built below is
        // structurally identical to the original fixed-orientation, floating packing
        // model (same variables, same constraints, same creation order), so the
        // existing T-Pk1..T-Pk20 behavior is preserved exactly.
        boolean anyRotatable = false;
        boolean anyPrecedence = false;
        boolean anyWeightBearing = false;
        for (BoxDef box : boxes) {
            if (box.rotatable()) anyRotatable = true;
            if (!box.aboveIds().isEmpty() || !box.belowIds().isEmpty()) anyPrecedence = true;
            if (box.maxStackedWeight() != Long.MAX_VALUE) anyWeightBearing = true;
        }
        // The pairwise below/above separation booleans only need to be addressable
        // (stored in arrays rather than inline locals) when a stacking sub-feature
        // references them. Rotation does not need them.
        boolean needsAddressableStacking = requireSupport || anyPrecedence || anyWeightBearing;

        CpModel model = new CpModel();

        // used[b] = 1 iff container b receives at least one box
        BoolVar[] used = new BoolVar[B];
        for (int b = 0; b < B; b++) {
            used[b] = model.newBoolVar("used_" + containers.get(b).id());
        }

        // x[i][b] = 1 iff box i is placed into container b
        BoolVar[][] x = new BoolVar[I][B];
        // Placement origin coordinates (null when the box does not fit in container b)
        IntVar[][] sx = new IntVar[I][B];
        IntVar[][] sy = new IntVar[I][B];
        IntVar[][] sz = new IntVar[I][B];
        // End coordinates per axis (null when the box does not fit)
        IntVar[][] ex = new IntVar[I][B];
        IntVar[][] ey = new IntVar[I][B];
        IntVar[][] ez = new IntVar[I][B];
        // Optional intervals per axis (null when the box does not fit); each interval
        // also enforces end = start + size when the placement is present.
        IntervalVar[][] ix = new IntervalVar[I][B];
        IntervalVar[][] iy = new IntervalVar[I][B];
        IntervalVar[][] iz = new IntervalVar[I][B];
        // Orientation literals and the fitting orientations they correspond to (non-null
        // only for rotatable boxes that fit in container b in at least one orientation).
        @SuppressWarnings("unchecked")
        List<BoolVar>[][] orientLits = (List<BoolVar>[][]) new List<?>[I][B];
        @SuppressWarnings("unchecked")
        List<Orientation>[][] fittingOrients = (List<Orientation>[][]) new List<?>[I][B];
        // fit-able boxes (fit in at least one container)
        boolean[] boxFitsSomewhere = new boolean[I];

        for (int i = 0; i < I; i++) {
            BoxDef box = boxes.get(i);
            for (int b = 0; b < B; b++) {
                ContainerDef cont = containers.get(b);
                x[i][b] = model.newBoolVar("x_" + box.id() + "_" + cont.id());

                if (box.rotatable()) {
                    // ── Rotation path ───────────────────────────────────────
                    // Keep only the orientations that fit container b on all three axes.
                    List<Orientation> fitting = new ArrayList<>();
                    List<BoolVar> lits = new ArrayList<>();
                    for (Orientation o : box.orientations()) {
                        if (o.length() <= cont.length()
                                && o.width() <= cont.width()
                                && o.height() <= cont.height()) {
                            fitting.add(o);
                            lits.add(model.newBoolVar(
                                    "o_" + box.id() + "_" + cont.id() + "_" + fitting.size()));
                        }
                    }
                    if (fitting.isEmpty()) {
                        // No orientation fits — placement is forbidden.
                        model.addEquality(x[i][b], 0);
                    } else {
                        boxFitsSomewhere[i] = true;
                        orientLits[i][b] = lits;
                        fittingOrients[i][b] = fitting;
                        // Exactly one orientation iff placed.
                        LinearExprBuilder osum = LinearExpr.newBuilder();
                        for (BoolVar l : lits) osum.add(l);
                        model.addEquality(osum, x[i][b]);

                        // Orientation-dependent size per axis. CP-SAT requires an
                        // interval's size to be a single IntVar (or constant), so each
                        // axis gets one size IntVar whose value is fixed to the chosen
                        // orientation's dimension via reified equalities. With exactly
                        // one orientation, exactly one equality binds, giving the same
                        // semantics as a sum(dim_k * orient_k) LinearExpr.
                        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
                        long maxX = 0, maxY = 0, maxZ = 0;
                        for (Orientation o : fitting) {
                            minX = Math.min(minX, o.length());
                            minY = Math.min(minY, o.width());
                            minZ = Math.min(minZ, o.height());
                            maxX = Math.max(maxX, o.length());
                            maxY = Math.max(maxY, o.width());
                            maxZ = Math.max(maxZ, o.height());
                        }
                        IntVar sizeX = model.newIntVar(minX, maxX, "szx_" + i + "_" + b);
                        IntVar sizeY = model.newIntVar(minY, maxY, "szy_" + i + "_" + b);
                        IntVar sizeZ = model.newIntVar(minZ, maxZ, "szz_" + i + "_" + b);
                        for (int k = 0; k < fitting.size(); k++) {
                            Orientation o = fitting.get(k);
                            model.addEquality(sizeX, o.length()).onlyEnforceIf(lits.get(k));
                            model.addEquality(sizeY, o.width()).onlyEnforceIf(lits.get(k));
                            model.addEquality(sizeZ, o.height()).onlyEnforceIf(lits.get(k));
                        }

                        sx[i][b] = model.newIntVar(0, cont.length(), "sx_" + i + "_" + b);
                        sy[i][b] = model.newIntVar(0, cont.width(),  "sy_" + i + "_" + b);
                        sz[i][b] = model.newIntVar(0, cont.height(), "sz_" + i + "_" + b);

                        ex[i][b] = model.newIntVar(0, cont.length(), "ex_" + i + "_" + b);
                        ey[i][b] = model.newIntVar(0, cont.width(),  "ey_" + i + "_" + b);
                        ez[i][b] = model.newIntVar(0, cont.height(), "ez_" + i + "_" + b);

                        ix[i][b] = model.newOptionalIntervalVar(
                                sx[i][b], sizeX, ex[i][b], x[i][b], "ix_" + i + "_" + b);
                        iy[i][b] = model.newOptionalIntervalVar(
                                sy[i][b], sizeY, ey[i][b], x[i][b], "iy_" + i + "_" + b);
                        iz[i][b] = model.newOptionalIntervalVar(
                                sz[i][b], sizeZ, ez[i][b], x[i][b], "iz_" + i + "_" + b);
                    }
                } else {
                    // ── Fixed-orientation path (identical to the original model) ──
                    boolean fits = box.length() <= cont.length()
                            && box.width() <= cont.width()
                            && box.height() <= cont.height();
                    if (fits) {
                        boxFitsSomewhere[i] = true;
                        long maxX = cont.length() - box.length();
                        long maxY = cont.width() - box.width();
                        long maxZ = cont.height() - box.height();

                        sx[i][b] = model.newIntVar(0, maxX, "sx_" + i + "_" + b);
                        sy[i][b] = model.newIntVar(0, maxY, "sy_" + i + "_" + b);
                        sz[i][b] = model.newIntVar(0, maxZ, "sz_" + i + "_" + b);

                        ex[i][b] = model.newIntVar(0, cont.length(), "ex_" + i + "_" + b);
                        ey[i][b] = model.newIntVar(0, cont.width(),  "ey_" + i + "_" + b);
                        ez[i][b] = model.newIntVar(0, cont.height(), "ez_" + i + "_" + b);

                        ix[i][b] = model.newOptionalIntervalVar(
                                sx[i][b], model.newConstant(box.length()), ex[i][b], x[i][b], "ix_" + i + "_" + b);
                        iy[i][b] = model.newOptionalIntervalVar(
                                sy[i][b], model.newConstant(box.width()),  ey[i][b], x[i][b], "iy_" + i + "_" + b);
                        iz[i][b] = model.newOptionalIntervalVar(
                                sz[i][b], model.newConstant(box.height()), ez[i][b], x[i][b], "iz_" + i + "_" + b);
                    } else {
                        // Box does not fit dimensionally — placement is forbidden.
                        model.addEquality(x[i][b], 0);
                    }
                }
            }
        }

        // (1) At-most-one-container per box.
        for (int i = 0; i < I; i++) {
            LinearExprBuilder sum = LinearExpr.newBuilder();
            for (int b = 0; b < B; b++) {
                sum.add(x[i][b]);
            }
            model.addLessOrEqual(sum, 1);
        }

        // For MinimizeContainersUsed this is classic bin packing: every box that fits
        // somewhere must be packed (otherwise minimizing containers trivially packs
        // nothing). For MaximizeVolumeUtilization the volume reward already drives
        // packing, so the at-most-one bound above suffices.
        if (goal instanceof PackingGoal.MinimizeContainersUsed) {
            for (int i = 0; i < I; i++) {
                if (boxFitsSomewhere[i]) {
                    LinearExprBuilder sum = LinearExpr.newBuilder();
                    for (int b = 0; b < B; b++) {
                        sum.add(x[i][b]);
                    }
                    model.addEquality(sum, 1);
                }
            }
        }

        // (2) Per-container weight limit.
        for (int b = 0; b < B; b++) {
            BoolVar[] vars = new BoolVar[I];
            long[] weights = new long[I];
            for (int i = 0; i < I; i++) {
                vars[i] = x[i][b];
                weights[i] = boxes.get(i).weight();
            }
            model.addLessOrEqual(buildWeightedSum(vars, weights), containers.get(b).maxWeight());
        }

        // (3) Per-container 3D no-overlap. For every pair of boxes that may both sit in
        // container b, at least one of the six axis-separation directions must hold
        // (i before j or j before i on X, Y, or Z). The disjunction is gated on both
        // placement literals so it only binds when both boxes are actually placed.
        //
        // below[i][j][b] (i<j) means "i is below j"; above[i][j][b] means "i is above j".
        // These are only stored in arrays when a stacking sub-feature needs them; when no
        // such feature is active they stay as inline locals — exactly as the original.
        BoolVar[][][] below = needsAddressableStacking ? new BoolVar[I][I][B] : null;
        BoolVar[][][] above = needsAddressableStacking ? new BoolVar[I][I][B] : null;
        for (int b = 0; b < B; b++) {
            for (int i = 0; i < I; i++) {
                if (ix[i][b] == null) continue;
                for (int j = i + 1; j < I; j++) {
                    if (ix[j][b] == null) continue;

                    BoolVar left  = model.newBoolVar("lx_" + i + "_" + j + "_" + b); // i before j on X
                    BoolVar right = model.newBoolVar("rx_" + i + "_" + j + "_" + b); // j before i on X
                    BoolVar front = model.newBoolVar("ly_" + i + "_" + j + "_" + b); // i before j on Y
                    BoolVar back  = model.newBoolVar("ry_" + i + "_" + j + "_" + b); // j before i on Y
                    BoolVar belowij = model.newBoolVar("lz_" + i + "_" + j + "_" + b); // i below j on Z
                    BoolVar aboveij = model.newBoolVar("rz_" + i + "_" + j + "_" + b); // j below i on Z

                    if (needsAddressableStacking) {
                        below[i][j][b] = belowij; // i below j
                        above[i][j][b] = aboveij; // i above j
                    }

                    model.addLessOrEqual(ex[i][b], sx[j][b]).onlyEnforceIf(left);
                    model.addLessOrEqual(ex[j][b], sx[i][b]).onlyEnforceIf(right);
                    model.addLessOrEqual(ey[i][b], sy[j][b]).onlyEnforceIf(front);
                    model.addLessOrEqual(ey[j][b], sy[i][b]).onlyEnforceIf(back);
                    model.addLessOrEqual(ez[i][b], sz[j][b]).onlyEnforceIf(belowij);
                    model.addLessOrEqual(ez[j][b], sz[i][b]).onlyEnforceIf(aboveij);

                    // When both boxes are present in container b, at least one direction holds.
                    model.addBoolOr(new Literal[]{left, right, front, back, belowij, aboveij})
                            .onlyEnforceIf(new Literal[]{x[i][b], x[j][b]});
                }
            }
        }

        // (3a) Support footprint — only when requireSupport. For each pair (i below j),
        // j's XY footprint must be contained in i's.
        if (requireSupport) {
            for (int b = 0; b < B; b++) {
                for (int i = 0; i < I; i++) {
                    if (ix[i][b] == null) continue;
                    for (int j = i + 1; j < I; j++) {
                        if (ix[j][b] == null) continue;
                        BoolVar bijBelow = below[i][j][b]; // i below j → j rests on i
                        model.addLessOrEqual(sx[i][b], sx[j][b]).onlyEnforceIf(bijBelow);
                        model.addLessOrEqual(ex[j][b], ex[i][b]).onlyEnforceIf(bijBelow);
                        model.addLessOrEqual(sy[i][b], sy[j][b]).onlyEnforceIf(bijBelow);
                        model.addLessOrEqual(ey[j][b], ey[i][b]).onlyEnforceIf(bijBelow);
                        // j below i → i rests on j
                        BoolVar bjiBelow = above[i][j][b]; // "i above j" means j below i
                        model.addLessOrEqual(sx[j][b], sx[i][b]).onlyEnforceIf(bjiBelow);
                        model.addLessOrEqual(ex[i][b], ex[j][b]).onlyEnforceIf(bjiBelow);
                        model.addLessOrEqual(sy[j][b], sy[i][b]).onlyEnforceIf(bjiBelow);
                        model.addLessOrEqual(ey[i][b], ey[j][b]).onlyEnforceIf(bjiBelow);
                    }
                }
            }
        }

        // (3b) Stacking precedence — above(id)/below(id). For a declared "i above j"
        // relationship, force the corresponding separation literal when both are placed.
        if (anyPrecedence) {
            for (int i = 0; i < I; i++) {
                BoxDef box = boxes.get(i);
                // box.aboveIds(): this box is above otherId → otherId below this box.
                for (String otherId : box.aboveIds()) {
                    int j = boxIndex(otherId);
                    forceRelative(model, x, below, above, i, j, B, /*iAboveJ=*/true);
                }
                // box.belowIds(): this box is below otherId → otherId above this box.
                for (String otherId : box.belowIds()) {
                    int j = boxIndex(otherId);
                    forceRelative(model, x, below, above, i, j, B, /*iAboveJ=*/false);
                }
            }
        }

        // (3c) Weight-bearing / load capacity. For each box i, the total weight of boxes
        // resting directly on it (boxes j with j above i) is bounded by maxStackedWeight.
        if (anyWeightBearing) {
            for (int i = 0; i < I; i++) {
                long cap = boxes.get(i).maxStackedWeight();
                if (cap == Long.MAX_VALUE) continue;
                for (int b = 0; b < B; b++) {
                    if (ix[i][b] == null) continue;
                    LinearExprBuilder load = LinearExpr.newBuilder();
                    for (int j = 0; j < I; j++) {
                        if (j == i) continue;
                        BoolVar jAboveI = jAboveLiteral(below, above, i, j, b);
                        if (jAboveI == null) continue;
                        load.addTerm(jAboveI, boxes.get(j).weight());
                    }
                    model.addLessOrEqual(load, cap).onlyEnforceIf(x[i][b]);
                }
            }
        }

        // (4) Link used[b]: any placement in container b forces used[b] = 1.
        for (int i = 0; i < I; i++) {
            for (int b = 0; b < B; b++) {
                model.addLessOrEqual(x[i][b], used[b]);
            }
        }

        // Objective — switch on the goal.
        switch (goal) {
            case PackingGoal.MinimizeContainersUsed minimizeContainersUsed -> {
                LinearExprBuilder obj = LinearExpr.newBuilder();
                for (int b = 0; b < B; b++) {
                    obj.add(used[b]);
                }
                model.minimize(obj);
            }
            case PackingGoal.MaximizeVolumeUtilization maximizeVolumeUtilization -> {
                LinearExprBuilder obj = LinearExpr.newBuilder();
                for (int i = 0; i < I; i++) {
                    long vol = boxes.get(i).volume();
                    for (int b = 0; b < B; b++) {
                        obj.addTerm(x[i][b], vol);
                    }
                }
                model.maximize(obj);
            }
            case null, default -> throw new ModelException("Unsupported packing goal: " + goal);
        }

        if (warmStart != null) applyWarmStart(model, x, sx, sy, sz, orientLits, fittingOrients);

        CpSatParams.Extractor<PackingSolution> extract = (v, status, runtime) -> {
            List<ContainerAssignment> assignments = new ArrayList<>();
            List<String> unassignedBoxes = new ArrayList<>();

            if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
                // Group placements by container (preserve declaration order, include empties)
                Map<String, List<ContainerAssignment.BoxPlacement>> placementsByContainer = new LinkedHashMap<>();
                for (ContainerDef cont : containers) {
                    placementsByContainer.put(cont.id(), new ArrayList<>());
                }

                for (int i = 0; i < I; i++) {
                    BoxDef box = boxes.get(i);
                    String placedIn = null;
                    long px = 0, py = 0, pz = 0;
                    int placedB = -1;
                    for (int b = 0; b < B; b++) {
                        if (v.booleanValue(x[i][b])) {
                            placedIn = containers.get(b).id();
                            px = v.value(sx[i][b]);
                            py = v.value(sy[i][b]);
                            pz = v.value(sz[i][b]);
                            placedB = b;
                            break;
                        }
                    }
                    if (placedIn != null) {
                        Orientation chosen = null;
                        long pl = box.length(), pw = box.width(), ph = box.height();
                        if (box.rotatable() && fittingOrients[i][placedB] != null) {
                            List<Orientation> fitting = fittingOrients[i][placedB];
                            List<BoolVar> lits = orientLits[i][placedB];
                            for (int k = 0; k < fitting.size(); k++) {
                                if (v.booleanValue(lits.get(k))) {
                                    chosen = fitting.get(k);
                                    pl = chosen.length();
                                    pw = chosen.width();
                                    ph = chosen.height();
                                    break;
                                }
                            }
                        }
                        placementsByContainer.get(placedIn).add(new ContainerAssignment.BoxPlacement(
                                box.id(), px, py, pz, pl, pw, ph, chosen));
                    } else {
                        unassignedBoxes.add(box.id());
                    }
                }

                // Build a ContainerAssignment per container (including empty ones)
                for (ContainerDef cont : containers) {
                    List<ContainerAssignment.BoxPlacement> placements = placementsByContainer.get(cont.id());
                    long usedVolume = 0;
                    long usedWeight = 0;
                    for (ContainerAssignment.BoxPlacement p : placements) {
                        usedVolume += p.length() * p.width() * p.height();
                        usedWeight += boxById(p.boxId()).weight();
                    }
                    assignments.add(new ContainerAssignment(
                            cont.id(), cont.length(), cont.width(), cont.height(), cont.maxWeight(),
                            List.copyOf(placements), usedVolume, usedWeight));
                }
            }

            OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                    ? OptionalDouble.of(v.objectiveValue())
                    : OptionalDouble.empty();

            return new PackingSolution(status, objValue, runtime, assignments, unassignedBoxes);
        };
        return params.solve(model, extract, listener);
    }

    /**
     * Forces the vertical separation literal for the pair (i,j) so that i sits above j
     * (when {@code iAboveJ}) or below j (otherwise), whenever both are placed in the same
     * container. Uses the no-overlap separation disjunction: forcing the "below"/"above"
     * literal selects the corresponding Z-separation, which also enforces ez-ordering.
     */
    private void forceRelative(CpModel model, BoolVar[][] x,
                               BoolVar[][][] below, BoolVar[][][] above,
                               int i, int j, int B, boolean iAboveJ) {
        for (int b = 0; b < B; b++) {
            BoolVar sep;
            if (i < j) {
                // pair (i, j): below[i][j] = i below j; above[i][j] = i above j.
                sep = iAboveJ ? above[i][j][b] : below[i][j][b];
            } else {
                // pair (j, i): below[j][i] = j below i; above[j][i] = j above i.
                // "i above j" => j below i => below[j][i]; "i below j" => j above i => above[j][i].
                sep = iAboveJ ? below[j][i][b] : above[j][i][b];
            }
            if (sep == null) continue; // pair cannot share this container (one doesn't fit)
            model.addBoolOr(new Literal[]{sep, x[i][b].not(), x[j][b].not()});
        }
    }

    /**
     * Returns the literal meaning "box j is directly above box i" in container b, using
     * the canonical (min,max)-indexed below/above arrays, or {@code null} if the pair
     * cannot share the container.
     */
    private BoolVar jAboveLiteral(BoolVar[][][] below, BoolVar[][][] above, int i, int j, int b) {
        if (i < j) {
            // pair (i, j): below[i][j] = i below j = j above i.
            return below[i][j][b];
        } else {
            // pair (j, i): above[j][i] = j above i.
            return above[j][i][b];
        }
    }

    /** Hints container, origin, and orientation for every box placed in a previous solution. */
    private void applyWarmStart(CpModel model, BoolVar[][] x, IntVar[][] sx, IntVar[][] sy, IntVar[][] sz,
                                List<BoolVar>[][] orientLits, List<Orientation>[][] fittingOrients) {
        for (ContainerAssignment prevCont : warmStart.assignments()) {
            int b = -1;
            for (int k = 0; k < containers.size(); k++) if (containers.get(k).id().equals(prevCont.containerId())) b = k;
            if (b < 0) continue;
            for (ContainerAssignment.BoxPlacement p : prevCont.boxes()) {
                int i = -1;
                for (int k = 0; k < boxes.size(); k++) if (boxes.get(k).id().equals(p.boxId())) i = k;
                if (i < 0 || sx[i][b] == null) continue;
                for (int bb = 0; bb < containers.size(); bb++) model.addHint(x[i][bb], bb == b ? 1 : 0);
                model.addHint(sx[i][b], p.x());
                model.addHint(sy[i][b], p.y());
                model.addHint(sz[i][b], p.z());
                if (p.orientation() != null && orientLits[i][b] != null) {
                    List<Orientation> fitting = fittingOrients[i][b];
                    for (int k = 0; k < fitting.size(); k++) {
                        model.addHint(orientLits[i][b].get(k), fitting.get(k).equals(p.orientation()) ? 1 : 0);
                    }
                }
            }
        }
    }

    private BoxDef boxById(String id) {
        for (BoxDef box : boxes) {
            if (box.id().equals(id)) return box;
        }
        throw new IllegalStateException("Unknown box id: " + id);
    }

    private int boxIndex(String id) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).id().equals(id)) return i;
        }
        throw new ModelException("Unknown box id referenced in above()/below(): '" + id + "'.");
    }

    /** Builds a weighted-sum LinearExpr from BoolVars and their coefficients. */
    private static LinearExpr buildWeightedSum(BoolVar[] vars, long[] coefficients) {
        LinearExprBuilder builder = LinearExpr.newBuilder();
        for (int i = 0; i < vars.length; i++) {
            builder.addTerm(vars[i], coefficients[i]);
        }
        return builder.build();
    }

    private void validate() {
        if (containers.isEmpty()) throw new ModelException("At least one container is required.");
        if (boxes.isEmpty()) throw new ModelException("At least one box is required.");

        // Volumes feed objective coefficients and reported totals; reject inputs whose
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

        // Resolve above()/below() ids and detect contradictory pairs.
        Set<String> ids = new HashSet<>();
        for (BoxDef box : boxes) ids.add(box.id());

        // Collect ordered "u above v" relationships from both above() and below().
        // aboveIds: this box (u) is above otherId (v)  => (u above v)
        // belowIds: this box (u) is below otherId (v)  => (v above u)
        Map<Long, String> abovePair = new HashMap<>(); // (u*I+v) -> descriptive key
        for (int u = 0; u < boxes.size(); u++) {
            BoxDef box = boxes.get(u);
            for (String v : box.aboveIds()) {
                if (!ids.contains(v)) {
                    throw new ModelException("above('" + v + "') on box '" + box.id()
                            + "' references an unknown box id.");
                }
                int vi = boxIndex(v);
                long key = pairKey(u, vi);
                abovePair.put(key, box.id() + " above " + v);
            }
            for (String v : box.belowIds()) {
                if (!ids.contains(v)) {
                    throw new ModelException("below('" + v + "') on box '" + box.id()
                            + "' references an unknown box id.");
                }
                int vi = boxIndex(v);
                // u below v => v above u => (v above u)
                long key = pairKey(vi, u);
                abovePair.put(key, v + " above " + box.id());
            }
        }
        // A pair declared in both directions (u above v AND v above u) is a contradiction.
        for (long key : abovePair.keySet()) {
            long[] uv = unpairKey(key);
            int u = (int) uv[0], v = (int) uv[1];
            if (u == v) {
                throw new ModelException("Box '" + boxes.get(u).id()
                        + "' cannot be declared above/below itself.");
            }
            long reverse = pairKey(v, u);
            if (abovePair.containsKey(reverse)) {
                throw new ModelException("Contradictory stacking precedence: '"
                        + boxes.get(u).id() + " above " + boxes.get(v).id()
                        + "' and its reverse are both declared.");
            }
        }
    }

    private long pairKey(int u, int v) {
        return ((long) u) * (boxes.size() + 1) + v;
    }

    private long[] unpairKey(long key) {
        int n = boxes.size() + 1;
        int u = (int) (key / n);
        int v = (int) (key % n);
        return new long[]{u, v};
    }
}

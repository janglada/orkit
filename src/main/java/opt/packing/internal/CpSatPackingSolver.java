package opt.packing.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.packing.ContainerAssignment;
import opt.packing.PackingGoal;
import opt.packing.PackingSolution;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a 3D bin-packing / container-loading model into CP-SAT and solves it.
 *
 * <p>One boolean variable {@code x[i][b]} per (box, container) indicates whether box
 * {@code i} is placed into container {@code b}. For each placement that physically fits
 * (box dimensions {@code <=} container dimensions on every axis), three optional
 * interval variables model the box's X/Y/Z footprint; per-container no-overlap
 * constraints prevent intersecting placements. A second boolean {@code used[b]} tracks
 * whether container {@code b} receives any box.
 *
 * <p>Objectives:
 * <ul>
 *   <li>{@link PackingGoal.MinimizeContainersUsed} — classic bin packing: every box that
 *       fits somewhere must be packed, and the number of non-empty containers is
 *       minimised.</li>
 *   <li>{@link PackingGoal.MaximizeVolumeUtilization} — maximise the total packed box
 *       volume (boxes may be left out when space or weight is tight).</li>
 * </ul>
 */
public final class CpSatPackingSolver {

    private final List<BoxDef> boxes;
    private final List<ContainerDef> containers;
    private final PackingGoal goal;

    private long timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    public CpSatPackingSolver(List<ContainerDef> containers, List<BoxDef> boxes, PackingGoal goal) {
        this.containers = List.copyOf(containers);
        this.boxes = List.copyOf(boxes);
        this.goal = goal;
    }

    /** Sets the maximum wall-clock time the solver may run before giving up. */
    public CpSatPackingSolver timeLimit(Duration limit) { this.timeLimitSeconds = limit.toSeconds(); return this; }
    /** Sets the number of parallel search workers (threads). */
    public CpSatPackingSolver workers(int n)            { this.workers = n;                            return this; }
    /** Sets the random seed used to make search reproducible. */
    public CpSatPackingSolver seed(long s)              { this.seed = s;                               return this; }
    /** Enables or disables logging of search progress to standard output. */
    public CpSatPackingSolver verbose(boolean v)        { this.verbose = v;                            return this; }

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
        // fit-able boxes (fit in at least one container)
        boolean[] boxFitsSomewhere = new boolean[I];

        for (int i = 0; i < I; i++) {
            BoxDef box = boxes.get(i);
            for (int b = 0; b < B; b++) {
                ContainerDef cont = containers.get(b);
                x[i][b] = model.newBoolVar("x_" + box.id() + "_" + cont.id());

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

        // (1) At-most-one-container per box.
        for (int i = 0; i < I; i++) {
            LinearExprBuilder sum = LinearExpr.newBuilder();
            for (int b = 0; b < B; b++) {
                sum.add(x[i][b]);
            }
            model.addLessOrEqual(sum, 1);
        }

        // For MinimizeContainersUsed this is classic bin packing: every box that fits
        // somewhere must be packed (otherwise minimising containers trivially packs
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
        // (i before j or j before i on X, Y, or Z). This is the correct 3D non-overlap
        // condition: boxes may share an axis range as long as they are disjoint on at
        // least one other axis. The disjunction is gated on both placement literals so
        // it only binds when both boxes are actually placed in container b.
        for (int b = 0; b < B; b++) {
            for (int i = 0; i < I; i++) {
                if (ix[i][b] == null) continue;
                for (int j = i + 1; j < I; j++) {
                    if (ix[j][b] == null) continue;

                    BoolVar left  = model.newBoolVar("lx_" + i + "_" + j + "_" + b); // i before j on X
                    BoolVar right = model.newBoolVar("rx_" + i + "_" + j + "_" + b); // j before i on X
                    BoolVar front = model.newBoolVar("ly_" + i + "_" + j + "_" + b); // i before j on Y
                    BoolVar back  = model.newBoolVar("ry_" + i + "_" + j + "_" + b); // j before i on Y
                    BoolVar below = model.newBoolVar("lz_" + i + "_" + j + "_" + b); // i below j on Z
                    BoolVar above = model.newBoolVar("rz_" + i + "_" + j + "_" + b); // j below i on Z

                    model.addLessOrEqual(ex[i][b], sx[j][b]).onlyEnforceIf(left);
                    model.addLessOrEqual(ex[j][b], sx[i][b]).onlyEnforceIf(right);
                    model.addLessOrEqual(ey[i][b], sy[j][b]).onlyEnforceIf(front);
                    model.addLessOrEqual(ey[j][b], sy[i][b]).onlyEnforceIf(back);
                    model.addLessOrEqual(ez[i][b], sz[j][b]).onlyEnforceIf(below);
                    model.addLessOrEqual(ez[j][b], sz[i][b]).onlyEnforceIf(above);

                    // When both boxes are present in container b, at least one direction holds.
                    model.addBoolOr(new Literal[]{left, right, front, back, below, above})
                            .onlyEnforceIf(new Literal[]{x[i][b], x[j][b]});
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

        // Solve
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(workers);
        solver.getParameters().setRandomSeed((int) seed);
        solver.getParameters().setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        CpSolverStatus cpStatus = solver.solve(model);
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        SolveStatus status = SolveStatus.mapStatus(cpStatus);
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
                for (int b = 0; b < B; b++) {
                    if (solver.booleanValue(x[i][b])) {
                        placedIn = containers.get(b).id();
                        px = solver.value(sx[i][b]);
                        py = solver.value(sy[i][b]);
                        pz = solver.value(sz[i][b]);
                        break;
                    }
                }
                if (placedIn != null) {
                    placementsByContainer.get(placedIn).add(new ContainerAssignment.BoxPlacement(
                            box.id(), px, py, pz, box.length(), box.width(), box.height()));
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
                    BoxDef box = boxById(p.boxId());
                    usedVolume += box.volume();
                    usedWeight += box.weight();
                }
                assignments.add(new ContainerAssignment(
                        cont.id(), cont.length(), cont.width(), cont.height(), cont.maxWeight(),
                        List.copyOf(placements), usedVolume, usedWeight));
            }
        }

        OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                ? OptionalDouble.of(solver.objectiveValue())
                : OptionalDouble.empty();

        return new PackingSolution(status, objValue, runtime, assignments, unassignedBoxes);
    }

    private BoxDef boxById(String id) {
        for (BoxDef box : boxes) {
            if (box.id().equals(id)) return box;
        }
        throw new IllegalStateException("Unknown box id: " + id);
    }

    private void validate() {
        if (containers.isEmpty()) throw new ModelException("At least one container is required.");
        if (boxes.isEmpty()) throw new ModelException("At least one box is required.");
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

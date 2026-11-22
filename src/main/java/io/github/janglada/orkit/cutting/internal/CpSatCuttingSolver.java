package io.github.janglada.orkit.cutting.internal;

import com.google.ortools.sat.*;
import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.cutting.CuttingGoal;
import io.github.janglada.orkit.cutting.CuttingSolution;
import io.github.janglada.orkit.cutting.StockAssignment;
import io.github.janglada.orkit.cutting.StockAssignment.CutPiece;
import io.github.janglada.orkit.internal.ConstraintGroups;
import io.github.janglada.orkit.internal.CpSatParams;
import io.github.janglada.orkit.internal.CpValues;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Compiles a cutting-stock model into CP-SAT and solves it.
 *
 * <p>Three regimes, selected from the input:
 * <ul>
 *   <li><b>1D</b> (every width is 0): an integer count per (piece type, roll) and a knapsack
 *       capacity per roll. Positions are not variables; pieces are laid end to end after the
 *       solve. Identical rolls are used in order to break symmetry.</li>
 *   <li><b>2D non-guillotine</b>: one optional rectangle per (piece instance, sheet) under a
 *       per-sheet {@code NoOverlap2D}, a redundant area constraint, optional 90-degree
 *       rotation, and symmetry breaking between identical sheets and identical pieces.</li>
 *   <li><b>2D guillotine</b>: the two-stage shelf model. Instances are sorted by width; an
 *       instance may open a shelf, other instances join an open shelf no narrower than they
 *       are, shelf lengths fit the sheet length and shelf widths stack within the sheet width.</li>
 * </ul>
 * <p>A kerf inflates every piece and every stock by the blade width on each cut axis, so
 * pieces are separated by the kerf but may touch the stock edge.
 */
public final class CpSatCuttingSolver {

    private final List<StockDef> stocks;
    private final List<PieceDef> pieces;
    private final CuttingGoal goal;
    private final long droppingPenalty;
    private final long kerf;
    private final boolean guillotine;
    private final CpSatParams params;
    private final Consumer<CuttingSolution> listener;
    private final CuttingSolution warmStart;

    /**
     * Creates a solver for the given rolls, piece types, and objective. Internal; obtain
     * instances via {@link io.github.janglada.orkit.cutting.CuttingBuilder#solver()}.
     */
    public CpSatCuttingSolver(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal, long droppingPenalty,
                              long kerf, boolean guillotine,
                              CpSatParams params, Consumer<CuttingSolution> listener, CuttingSolution warmStart) {
        this.stocks = List.copyOf(stocks);
        this.pieces = List.copyOf(pieces);
        this.goal = goal;
        this.droppingPenalty = droppingPenalty;
        this.kerf = kerf;
        this.guillotine = guillotine;
        this.params = (params == null) ? new CpSatParams() : params;
        this.listener = listener;
        this.warmStart = warmStart;
    }

    /**
     * Builds the CP-SAT model, solves it, and maps the result back to roll/piece
     * identifiers. Returns one {@link StockAssignment} per declared roll (empty rolls
     * included, in declaration order) and lists piece instances that could not be placed.
     */
    public CuttingSolution solve() {
        validate();
        boolean any2D = stocks.stream().anyMatch(s -> s.width() > 0)
                || pieces.stream().anyMatch(p -> p.width() > 0);
        if (!any2D) return new OneD(this).solve();
        require2D();
        return guillotine ? new Guillotine(this).solve() : new TwoD(this).solve();
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private void validate() {
        if (stocks.isEmpty()) throw new ModelException("At least one stock roll is required.");
        if (pieces.isEmpty()) throw new ModelException("At least one piece is required.");
        if (goal == null) throw new ModelException("Cutting goal must be set.");
        // Inflated areas are objective and constraint coefficients; reject inputs that would wrap around.
        long totalArea = 0;
        for (StockDef s : stocks) {
            long l = Validate.add(s.length(), kerf, "Stock '" + s.id() + "' length plus kerf");
            long w = Validate.add(s.width(), kerf, "Stock '" + s.id() + "' width plus kerf");
            totalArea = Validate.add(totalArea, Validate.multiply(l, w, "Stock '" + s.id() + "' area"), "Total stock area");
        }
        for (PieceDef p : pieces) {
            long l = Validate.add(p.length(), kerf, "Piece '" + p.id() + "' length plus kerf");
            long w = Validate.add(p.width(), kerf, "Piece '" + p.id() + "' width plus kerf");
            Validate.multiply(Validate.multiply(l, w, "Piece '" + p.id() + "' area"), p.quantity(),
                    "Piece '" + p.id() + "' total area");
        }
    }

    /** In 2D, every stock and every piece must declare a width. */
    private void require2D() {
        for (StockDef stock : stocks) {
            if (stock.width() <= 0) {
                throw new ModelException("2D cutting requires every stock and piece to declare a width"
                        + " (stock '" + stock.id() + "' has no width).");
            }
        }
        for (PieceDef piece : pieces) {
            if (piece.width() <= 0) {
                throw new ModelException("2D cutting requires every stock and piece to declare a width"
                        + " (piece '" + piece.id() + "' has no width).");
            }
        }
    }

    /** One {@code used[r]} literal per roll, ordered between consecutive rolls of the same stock type. */
    private BoolVar[] usedVars(CpModel model) {
        BoolVar[] used = new BoolVar[stocks.size()];
        for (int r = 0; r < used.length; r++) {
            used[r] = model.newBoolVar("used_" + stocks.get(r).id());
            if (r > 0 && sameType(r - 1, r)) model.addGreaterOrEqual(used[r - 1], used[r]);
        }
        return used;
    }

    private boolean sameType(int r1, int r2) {
        return stocks.get(r1).type().equals(stocks.get(r2).type());
    }

    /** Minimizes the goal over {@code used}, plus the drop penalty on {@code dropped} (may be null). */
    private void minimize(CpModel model, BoolVar[] used, LinearArgument[] dropped, boolean twoD) {
        LinearExprBuilder obj = LinearExpr.newBuilder();
        for (int r = 0; r < used.length; r++) {
            StockDef s = stocks.get(r);
            long coef = switch (goal) {
                // Total stock consumed; total piece size is constant, so this minimizes waste.
                case CuttingGoal.MinimizeWaste w     -> twoD ? s.area() : s.length();
                case CuttingGoal.MinimizeStockUsed u -> 1;
                case CuttingGoal.MinimizeTotalCost c -> s.cost();
            };
            obj.addTerm(used[r], coef);
        }
        if (dropped != null) {
            for (LinearArgument d : dropped) obj.addTerm(d, droppingPenalty);
        }
        model.minimize(obj);
    }

    private static boolean solved(SolveStatus status) {
        return status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE;
    }

    private CuttingSolution result(CpValues v, SolveStatus status, Duration runtime, SolveDiagnostics diag,
                                   List<StockAssignment> assignments, List<String> unplaced) {
        OptionalDouble objValue = solved(status) ? OptionalDouble.of(v.objectiveValue()) : OptionalDouble.empty();
        return new CuttingSolution(status, objValue, runtime, assignments, unplaced, diag);
    }

    /** Roll index by instance id, for warm starts. */
    private Map<String, Integer> stockIndex() {
        Map<String, Integer> out = new HashMap<>();
        for (int r = 0; r < stocks.size(); r++) out.put(stocks.get(r).id(), r);
        return out;
    }

    // ── 1D: counts per (piece type, roll) ───────────────────────────────────

    private static final class OneD {
        private final CpSatCuttingSolver s;

        OneD(CpSatCuttingSolver s) {
            this.s = s;
        }

        CuttingSolution solve() {
            List<PieceDef> types = s.pieces;
            List<StockDef> rolls = s.stocks;
            int numTypes = types.size();
            int numRolls = rolls.size();
            long k = s.kerf;
            CpModel model = new CpModel();
            ConstraintGroups groups = s.params.groups(model);

            // n[t][r] = pieces of type t cut from roll r
            IntVar[][] n = new IntVar[numTypes][numRolls];
            for (int t = 0; t < numTypes; t++) {
                PieceDef p = types.get(t);
                for (int r = 0; r < numRolls; r++) {
                    long fit = (rolls.get(r).length() + k) / (p.length() + k);
                    n[t][r] = model.newIntVar(0, Math.min(p.quantity(), fit), "n_" + p.id() + "_" + rolls.get(r).id());
                }
            }

            // Every piece is cut, or dropped at a penalty when dropping is enabled.
            IntVar[] dropped = s.droppingPenalty > 0 ? new IntVar[numTypes] : null;
            for (int t = 0; t < numTypes; t++) {
                LinearExprBuilder cut = LinearExpr.newBuilder();
                for (int r = 0; r < numRolls; r++) cut.add(n[t][r]);
                if (dropped != null) {
                    dropped[t] = model.newIntVar(0, types.get(t).quantity(), "dropped_" + types.get(t).id());
                    cut.add(dropped[t]);
                }
                groups.guard(model.addEquality(cut, types.get(t).quantity()),
                        "all " + types.get(t).quantity() + " piece(s) '" + types.get(t).id() + "' must be cut");
            }

            // Capacity: k pieces need their lengths plus k-1 kerfs. Identical rolls are ordered by
            // use only: ordering them by load as well makes even a first solution hard to find.
            BoolVar[] used = s.usedVars(model);
            for (int r = 0; r < numRolls; r++) {
                LinearExprBuilder inflated = LinearExpr.newBuilder();
                LinearExprBuilder count = LinearExpr.newBuilder();
                for (int t = 0; t < numTypes; t++) {
                    inflated.addTerm(n[t][r], types.get(t).length() + k);
                    count.add(n[t][r]);
                }
                inflated.addTerm(used[r], -(rolls.get(r).length() + k));
                model.addLessOrEqual(inflated, 0);
                model.addLessOrEqual(used[r], count);
            }

            s.minimize(model, used, dropped, false);
            if (s.warmStart != null) hint(model, n, used);

            return s.params.solve(model, (v, status, runtime, diag) -> {
                List<StockAssignment> assignments = new ArrayList<>();
                List<String> unplaced = new ArrayList<>();
                if (solved(status)) {
                    int[] next = new int[numTypes];
                    Arrays.fill(next, 1);
                    for (int r = 0; r < numRolls; r++) {
                        StockDef roll = rolls.get(r);
                        List<CutPiece> cuts = new ArrayList<>();
                        long offset = 0;
                        long cutLength = 0;
                        for (int t = 0; t < numTypes; t++) {
                            PieceDef p = types.get(t);
                            for (long c = v.value(n[t][r]); c > 0; c--) {
                                cuts.add(new CutPiece(p.id() + "#" + next[t]++, p.length(), offset, 0L, 0, false));
                                offset += p.length() + k;
                                cutLength += p.length();
                            }
                        }
                        long waste = cuts.isEmpty() ? 0L : roll.length() - cutLength;
                        assignments.add(new StockAssignment(roll.id(), roll.length(), 0L, roll.cost(), cuts, waste));
                    }
                    for (int t = 0; t < numTypes; t++) {
                        for (int i = next[t]; i <= types.get(t).quantity(); i++) unplaced.add(types.get(t).id() + "#" + i);
                    }
                }
                return s.result(v, status, runtime, diag, assignments, unplaced);
            }, s.listener);
        }

        /** Hints the per-roll counts of each piece type from a previous 1D solution. */
        private void hint(CpModel model, IntVar[][] n, BoolVar[] used) {
            Map<String, Integer> rollIdx = s.stockIndex();
            Map<String, Integer> typeIdx = new HashMap<>();
            for (int t = 0; t < s.pieces.size(); t++) typeIdx.put(s.pieces.get(t).id(), t);
            long[][] counts = new long[s.pieces.size()][s.stocks.size()];
            boolean[] usedPrev = new boolean[s.stocks.size()];
            for (StockAssignment a : s.warmStart.assignments()) {
                Integer r = rollIdx.get(a.stockId());
                if (r == null) continue;
                for (CutPiece c : a.pieces()) {
                    Integer t = typeIdx.get(c.pieceType());
                    if (t == null) continue;
                    counts[t][r]++;
                    usedPrev[r] = true;
                }
            }
            for (int r = 0; r < s.stocks.size(); r++) {
                model.addHint(used[r], usedPrev[r] ? 1 : 0);
                for (int t = 0; t < s.pieces.size(); t++) model.addHint(n[t][r], counts[t][r]);
            }
        }
    }

    // ── 2D piece instances ──────────────────────────────────────────────────

    /** One piece instance in 2D: its type index, its id, its declared extents and whether it may turn. */
    private record Instance(int type, String id, long length, long width, boolean rotatable) {}

    private List<Instance> instances() {
        List<Instance> out = new ArrayList<>();
        for (int t = 0; t < pieces.size(); t++) {
            PieceDef p = pieces.get(t);
            for (int q = 1; q <= p.quantity(); q++) {
                // A square piece looks the same turned; treat it as fixed.
                out.add(new Instance(t, p.id() + "#" + q, p.length(), p.width(),
                        p.rotatable() && p.length() != p.width()));
            }
        }
        return out;
    }

    /** The assignment of one 2D sheet; waste is its area minus the pieces' area. */
    private static StockAssignment sheetAssignment(StockDef sheet, List<CutPiece> cuts) {
        long cutArea = cuts.stream().mapToLong(c -> c.length() * c.width()).sum();
        long waste = cuts.isEmpty() ? 0L : sheet.area() - cutArea;
        return new StockAssignment(sheet.id(), sheet.length(), sheet.width(), sheet.cost(), cuts, waste);
    }

    // ── 2D non-guillotine ───────────────────────────────────────────────────

    private static final class TwoD {
        private final CpSatCuttingSolver s;

        TwoD(CpSatCuttingSolver s) {
            this.s = s;
        }

        CuttingSolution solve() {
            List<Instance> inst = s.instances();
            List<StockDef> sheets = s.stocks;
            int numPieces = inst.size();
            int numSheets = sheets.size();
            long k = s.kerf;
            CpModel model = new CpModel();
            ConstraintGroups groups = s.params.groups(model);

            BoolVar[][] x = new BoolVar[numPieces][numSheets];     // piece p on sheet r
            BoolVar[][] rot = new BoolVar[numPieces][numSheets];   // turned; null when the orientation is fixed
            boolean[][] fixedRot = new boolean[numPieces][numSheets];
            IntVar[][] sx = new IntVar[numPieces][numSheets];
            IntVar[][] sy = new IntVar[numPieces][numSheets];
            IntervalVar[][] ix = new IntervalVar[numPieces][numSheets];
            IntervalVar[][] iy = new IntervalVar[numPieces][numSheets];

            for (int p = 0; p < numPieces; p++) {
                Instance pc = inst.get(p);
                for (int r = 0; r < numSheets; r++) {
                    StockDef sheet = sheets.get(r);
                    boolean normal = pc.length() <= sheet.length() && pc.width() <= sheet.width();
                    boolean turned = pc.rotatable() && pc.width() <= sheet.length() && pc.length() <= sheet.width();
                    if (!normal && !turned) continue;
                    String tag = pc.id() + "_" + sheet.id();
                    x[p][r] = model.newBoolVar("x_" + tag);
                    long lenK = pc.length() + k;
                    long widK = pc.width() + k;
                    LinearArgument sizeX;
                    LinearArgument sizeY;
                    long minX;
                    long minY;
                    if (normal && turned) {
                        rot[p][r] = model.newBoolVar("rot_" + tag);
                        sizeX = LinearExpr.affine(rot[p][r], widK - lenK, lenK);
                        sizeY = LinearExpr.affine(rot[p][r], lenK - widK, widK);
                        minX = Math.min(lenK, widK);
                        minY = minX;
                    } else {
                        fixedRot[p][r] = turned;
                        minX = turned ? widK : lenK;
                        minY = turned ? lenK : widK;
                        sizeX = LinearExpr.constant(minX);
                        sizeY = LinearExpr.constant(minY);
                    }
                    long sheetX = sheet.length() + k;
                    long sheetY = sheet.width() + k;
                    sx[p][r] = model.newIntVar(0, sheetX - minX, "sx_" + tag);
                    sy[p][r] = model.newIntVar(0, sheetY - minY, "sy_" + tag);
                    IntVar ex = model.newIntVar(0, sheetX, "ex_" + tag);
                    IntVar ey = model.newIntVar(0, sheetY, "ey_" + tag);
                    ix[p][r] = model.newOptionalIntervalVar(sx[p][r], sizeX, ex, x[p][r], "ix_" + tag);
                    iy[p][r] = model.newOptionalIntervalVar(sy[p][r], sizeY, ey, x[p][r], "iy_" + tag);
                }
            }

            // Each piece on exactly one sheet, or dropped at a penalty when dropping is enabled.
            BoolVar[] dropped = s.droppingPenalty > 0 ? new BoolVar[numPieces] : null;
            LinearExpr previousIndex = null;
            for (int p = 0; p < numPieces; p++) {
                LinearExprBuilder placed = LinearExpr.newBuilder();
                LinearExprBuilder index = LinearExpr.newBuilder();
                for (int r = 0; r < numSheets; r++) {
                    if (x[p][r] == null) continue;
                    placed.add(x[p][r]);
                    index.addTerm(x[p][r], r);
                }
                if (dropped != null) {
                    dropped[p] = model.newBoolVar("dropped_" + inst.get(p).id());
                    placed.add(dropped[p]);
                    index.addTerm(dropped[p], numSheets);
                }
                groups.guard(model.addEquality(placed, 1), "piece '" + inst.get(p).id() + "' must be cut");
                LinearExpr sheetIndex = index.build();
                // Identical instances are interchangeable: number them in sheet order.
                if (p > 0 && inst.get(p - 1).type() == inst.get(p).type()) {
                    model.addLessOrEqual(previousIndex, sheetIndex);
                }
                previousIndex = sheetIndex;
            }

            BoolVar[] used = s.usedVars(model);
            for (int r = 0; r < numSheets; r++) {
                StockDef sheet = sheets.get(r);
                NoOverlap2dConstraint noOverlap = model.addNoOverlap2D();
                LinearExprBuilder area = LinearExpr.newBuilder();
                LinearExprBuilder count = LinearExpr.newBuilder();
                for (int p = 0; p < numPieces; p++) {
                    if (x[p][r] == null) continue;
                    noOverlap.addRectangle(ix[p][r], iy[p][r]);
                    model.addLessOrEqual(x[p][r], used[r]);
                    Instance pc = inst.get(p);
                    area.addTerm(x[p][r], (pc.length() + k) * (pc.width() + k));
                    count.add(x[p][r]);
                }
                // Redundant: the inflated pieces on a sheet cannot exceed its inflated area.
                area.addTerm(used[r], -(sheet.length() + k) * (sheet.width() + k));
                model.addLessOrEqual(area, 0);
                model.addLessOrEqual(used[r], count);
            }

            s.minimize(model, used, dropped, true);
            if (s.warmStart != null) hint(model, inst, x, rot, sx, sy);

            return s.params.solve(model, (v, status, runtime, diag) -> {
                List<StockAssignment> assignments = new ArrayList<>();
                List<String> unplaced = new ArrayList<>();
                if (solved(status)) {
                    List<List<CutPiece>> bySheet = new ArrayList<>();
                    for (int r = 0; r < numSheets; r++) bySheet.add(new ArrayList<>());
                    for (int p = 0; p < numPieces; p++) {
                        Instance pc = inst.get(p);
                        int at = -1;
                        for (int r = 0; r < numSheets && at < 0; r++) {
                            if (x[p][r] != null && v.booleanValue(x[p][r])) at = r;
                        }
                        if (at < 0) {
                            unplaced.add(pc.id());
                            continue;
                        }
                        boolean turned = rot[p][at] != null ? v.booleanValue(rot[p][at]) : fixedRot[p][at];
                        long len = turned ? pc.width() : pc.length();
                        long wid = turned ? pc.length() : pc.width();
                        bySheet.get(at).add(new CutPiece(pc.id(), len, v.value(sx[p][at]), v.value(sy[p][at]),
                                (int) wid, turned));
                    }
                    for (int r = 0; r < numSheets; r++) {
                        assignments.add(sheetAssignment(sheets.get(r), bySheet.get(r)));
                    }
                }
                return s.result(v, status, runtime, diag, assignments, unplaced);
            }, s.listener);
        }

        /** Hints sheet, origin and orientation for every piece placed in a previous 2D solution. */
        private void hint(CpModel model, List<Instance> inst, BoolVar[][] x, BoolVar[][] rot,
                          IntVar[][] sx, IntVar[][] sy) {
            Map<String, Integer> sheetIdx = s.stockIndex();
            Map<String, Integer> pieceIdx = new HashMap<>();
            for (int p = 0; p < inst.size(); p++) pieceIdx.put(inst.get(p).id(), p);
            for (StockAssignment a : s.warmStart.assignments()) {
                Integer r = sheetIdx.get(a.stockId());
                if (r == null) continue;
                for (CutPiece c : a.pieces()) {
                    Integer p = pieceIdx.get(c.pieceId());
                    if (p == null || x[p][r] == null) continue;
                    for (int rr = 0; rr < x[p].length; rr++) {
                        if (x[p][rr] != null) model.addHint(x[p][rr], rr == r ? 1 : 0);
                    }
                    model.addHint(sx[p][r], c.x());
                    model.addHint(sy[p][r], c.y());
                    if (rot[p][r] != null) model.addHint(rot[p][r], c.rotated() ? 1 : 0);
                }
            }
        }
    }

    // ── 2D guillotine: two-stage shelves ────────────────────────────────────

    private static final class Guillotine {
        private final CpSatCuttingSolver s;

        Guillotine(CpSatCuttingSolver s) {
            this.s = s;
        }

        CuttingSolution solve() {
            // Widest first: a shelf is as wide as the instance that opens it.
            List<Instance> inst = new ArrayList<>(s.instances());
            inst.sort(Comparator.comparingLong(Instance::width).reversed());
            List<StockDef> sheets = s.stocks;
            int numPieces = inst.size();
            int numSheets = sheets.size();
            long k = s.kerf;
            CpModel model = new CpModel();
            ConstraintGroups groups = s.params.groups(model);

            BoolVar[] open = new BoolVar[numPieces];                   // instance i opens a shelf
            BoolVar[][] shelfOn = new BoolVar[numPieces][numSheets];   // shelf i lies on sheet r
            BoolVar[][] joins = new BoolVar[numPieces][numPieces];     // [i][j]: i joins the shelf j opened (j < i)
            BoolVar[] used = s.usedVars(model);
            BoolVar[] dropped = s.droppingPenalty > 0 ? new BoolVar[numPieces] : null;

            for (int i = 0; i < numPieces; i++) {
                Instance pc = inst.get(i);
                open[i] = model.newBoolVar("open_" + pc.id());
                LinearExprBuilder onSheet = LinearExpr.newBuilder();
                for (int r = 0; r < numSheets; r++) {
                    StockDef sheet = sheets.get(r);
                    if (pc.length() > sheet.length() || pc.width() > sheet.width()) continue;
                    shelfOn[i][r] = model.newBoolVar("shelf_" + pc.id() + "_" + sheet.id());
                    onSheet.add(shelfOn[i][r]);
                    model.addLessOrEqual(shelfOn[i][r], used[r]);
                }
                model.addEquality(onSheet, open[i]);

                // Placed exactly once: as a shelf opener, inside an earlier (wider) shelf, or dropped.
                LinearExprBuilder placed = LinearExpr.newBuilder().add(open[i]);
                for (int j = 0; j < i; j++) {
                    joins[i][j] = model.newBoolVar("join_" + pc.id() + "_" + inst.get(j).id());
                    model.addImplication(joins[i][j], open[j]);
                    placed.add(joins[i][j]);
                }
                if (dropped != null) {
                    dropped[i] = model.newBoolVar("dropped_" + pc.id());
                    placed.add(dropped[i]);
                }
                groups.guard(model.addEquality(placed, 1), "piece '" + pc.id() + "' must be cut");
            }

            // Shelf length: the opener and its members, kerf-separated, fit the sheet length.
            for (int j = 0; j < numPieces; j++) {
                LinearExprBuilder length = LinearExpr.newBuilder().addTerm(open[j], inst.get(j).length() + k);
                for (int i = j + 1; i < numPieces; i++) length.addTerm(joins[i][j], inst.get(i).length() + k);
                for (int r = 0; r < numSheets; r++) {
                    if (shelfOn[j][r] != null) length.addTerm(shelfOn[j][r], -(sheets.get(r).length() + k));
                }
                model.addLessOrEqual(length, 0);
            }

            // Sheet width: the shelves stacked on a sheet fit its width.
            for (int r = 0; r < numSheets; r++) {
                LinearExprBuilder width = LinearExpr.newBuilder();
                LinearExprBuilder count = LinearExpr.newBuilder();
                for (int j = 0; j < numPieces; j++) {
                    if (shelfOn[j][r] == null) continue;
                    width.addTerm(shelfOn[j][r], inst.get(j).width() + k);
                    count.add(shelfOn[j][r]);
                }
                width.addTerm(used[r], -(sheets.get(r).width() + k));
                model.addLessOrEqual(width, 0);
                model.addLessOrEqual(used[r], count);
            }

            s.minimize(model, used, dropped, true);
            if (s.warmStart != null) hintUsed(model, used);

            return s.params.solve(model, (v, status, runtime, diag) -> {
                List<StockAssignment> assignments = new ArrayList<>();
                List<String> unplaced = new ArrayList<>();
                if (solved(status)) {
                    List<List<CutPiece>> bySheet = new ArrayList<>();
                    long[] nextY = new long[numSheets];
                    for (int r = 0; r < numSheets; r++) bySheet.add(new ArrayList<>());
                    for (int j = 0; j < numPieces; j++) {
                        if (!v.booleanValue(open[j])) continue;
                        int r = 0;
                        while (shelfOn[j][r] == null || !v.booleanValue(shelfOn[j][r])) r++;
                        long y = nextY[r];
                        long xOff = 0;
                        for (int i = j; i < numPieces; i++) {
                            boolean member = (i == j) || v.booleanValue(joins[i][j]);
                            if (!member) continue;
                            Instance pc = inst.get(i);
                            bySheet.get(r).add(new CutPiece(pc.id(), pc.length(), xOff, y, (int) pc.width(), false));
                            xOff += pc.length() + k;
                        }
                        nextY[r] += inst.get(j).width() + k;
                    }
                    if (dropped != null) {
                        for (int i = 0; i < numPieces; i++) {
                            if (v.booleanValue(dropped[i])) unplaced.add(inst.get(i).id());
                        }
                    }
                    for (int r = 0; r < numSheets; r++) {
                        assignments.add(sheetAssignment(sheets.get(r), bySheet.get(r)));
                    }
                }
                return s.result(v, status, runtime, diag, assignments, unplaced);
            }, s.listener);
        }

        /** Hints which sheets a previous solution used; the shelf structure is left to the search. */
        private void hintUsed(CpModel model, BoolVar[] used) {
            Map<String, Integer> sheetIdx = s.stockIndex();
            boolean[] usedPrev = new boolean[used.length];
            for (StockAssignment a : s.warmStart.assignments()) {
                Integer r = sheetIdx.get(a.stockId());
                if (r != null && !a.pieces().isEmpty()) usedPrev[r] = true;
            }
            for (int r = 0; r < used.length; r++) model.addHint(used[r], usedPrev[r] ? 1 : 0);
        }
    }
}

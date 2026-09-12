package opt.cutting.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.internal.CpSatParams;
import java.util.function.Consumer;
import opt.cutting.CuttingGoal;
import opt.cutting.CuttingSolution;
import opt.cutting.StockAssignment;
import opt.cutting.StockAssignment.CutPiece;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a cutting-stock model into CP-SAT and solves it.
 *
 * <p>Supports two regimes, selected automatically from the input:
 * <ul>
 *   <li><b>1D</b> (the default): every stock and piece has {@code width == 0}. Each stock
 *       roll is a 1D "machine" and each piece an optional interval on that roll; a boolean
 *       {@code x[p][r]} selects whether piece {@code p} is cut from roll {@code r}, and
 *       per-roll {@code NoOverlap} guarantees cuts on the same roll do not overlap.</li>
 *   <li><b>2D non-guillotine</b>: every stock and piece declares a {@code width > 0}. Each
 *       sheet is an X/Y plane; each (piece, sheet) placement gets two optional intervals
 *       (length axis and width axis) and per-sheet pairwise 2D no-overlap constraints
 *       (left/right/front/back separation) prevent overlapping placements. Guillotine
 *       cutting is not modelled.</li>
 * </ul>
 * <p>Every piece is required (assigned to exactly one sheet/roll) unless dropping is enabled.
 */
public final class CpSatCuttingSolver {

    private final List<StockDef> stocks;
    private final List<PieceDef> pieces;
    private final CuttingGoal goal;
    private final long droppingPenalty;
    private final CpSatParams params;
    private final Consumer<CuttingSolution> listener;
    private final CuttingSolution warmStart;


    /**
     * Creates a solver for the given expanded rolls, piece instances, and objective.
     * Internal; obtain instances via {@link opt.cutting.CuttingBuilder#solver()}.
     */
    public CpSatCuttingSolver(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal, long droppingPenalty,
                              CpSatParams params, Consumer<CuttingSolution> listener, CuttingSolution warmStart) {
        this.stocks = List.copyOf(stocks);
        this.pieces = List.copyOf(pieces);
        this.goal = goal;
        this.droppingPenalty = droppingPenalty;
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
        return any2D ? solve2D() : solve1D();
    }

    // ── 1D path (unchanged behavior) ─────────────────────────────────────────

    private CuttingSolution solve1D() {
        int P = pieces.size();
        int R = stocks.size();

        CpModel model = new CpModel();

        // x[p][r] = 1 iff piece p is cut from roll r
        BoolVar[][] x = new BoolVar[P][R];
        // s[p][r], e[p][r], iv[p][r] only created when the piece fits on the roll
        IntVar[][] s = new IntVar[P][R];
        IntVar[][] e = new IntVar[P][R];
        IntervalVar[][] iv = new IntervalVar[P][R];

        for (int p = 0; p < P; p++) {
            PieceDef piece = pieces.get(p);
            for (int r = 0; r < R; r++) {
                StockDef roll = stocks.get(r);
                x[p][r] = model.newBoolVar("x_" + piece.id() + "_" + roll.id());
                if (piece.length() <= roll.length()) {
                    s[p][r] = model.newIntVar(0, roll.length() - piece.length(),
                            "s_" + piece.id() + "_" + roll.id());
                    e[p][r] = model.newIntVar(piece.length(), roll.length(),
                            "e_" + piece.id() + "_" + roll.id());
                    model.addEquality(e[p][r], LinearExpr.sum(new IntVar[]{s[p][r], model.newConstant(piece.length())}));
                    iv[p][r] = model.newOptionalIntervalVar(s[p][r], model.newConstant(piece.length()),
                            e[p][r], x[p][r], "iv_" + piece.id() + "_" + roll.id());
                } else {
                    // Piece too long for this roll: forbid the assignment.
                    model.addEquality(x[p][r], 0);
                }
            }
        }

        // Each piece is assigned to at most one roll.
        // When dropping is enabled, pieces may be left unplaced (dropped[p] = 1).
        BoolVar[] dropped = null;
        if (droppingPenalty > 0) {
            dropped = new BoolVar[P];
        }
        for (int p = 0; p < P; p++) {
            BoolVar[] row = new BoolVar[R];
            System.arraycopy(x[p], 0, row, 0, R);
            if (droppingPenalty > 0) {
                dropped[p] = model.newBoolVar("dropped_" + pieces.get(p).id());
                // sum(x[p][*]) + dropped[p] = 1  =>  dropped[p] = 1 iff piece is unplaced
                BoolVar[] rowWithDrop = new BoolVar[R + 1];
                System.arraycopy(x[p], 0, rowWithDrop, 0, R);
                rowWithDrop[R] = dropped[p];
                model.addEquality(buildWeightedSum(rowWithDrop, ones(R + 1)), 1);
                // sum(x[p][*]) <= 1 as a safety net (the equality already implies it)
                model.addLessOrEqual(buildWeightedSum(row, ones(R)), 1);
            } else {
                model.addEquality(buildWeightedSum(row, ones(R)), 1);
            }
        }

        // used[r] = 1 iff roll r is consumed
        BoolVar[] used = new BoolVar[R];
        for (int r = 0; r < R; r++) {
            used[r] = model.newBoolVar("used_" + stocks.get(r).id());
        }

        // Per-roll NoOverlap on the optional intervals + link x[p][r] <= used[r].
        for (int r = 0; r < R; r++) {
            List<IntervalVar> rollIntervals = new ArrayList<>();
            for (int p = 0; p < P; p++) {
                if (iv[p][r] != null) {
                    rollIntervals.add(iv[p][r]);
                    model.addLessOrEqual(x[p][r], used[r]);
                }
            }
            if (!rollIntervals.isEmpty()) {
                model.addNoOverlap(rollIntervals.toArray(new IntervalVar[0]));
            }
        }

        // Objective
        LinearExprBuilder obj = LinearExpr.newBuilder();
        switch (goal) {
            case CuttingGoal.MinimizeWaste minimizeWaste -> {
                // Minimize total length of stock consumed (= total waste + constant total piece length).
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], stocks.get(r).length());
                }
            }
            case CuttingGoal.MinimizeStockUsed minimizeStockUsed -> {
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], 1);
                }
            }
            case CuttingGoal.MinimizeTotalCost minimizeTotalCost -> {
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], stocks.get(r).cost());
                }
            }
            case null, default -> throw new ModelException("Unsupported cutting goal: " + goal);
        }
        // When dropping is enabled, add a penalty for each dropped piece.
        if (droppingPenalty > 0) {
            for (int p = 0; p < P; p++) {
                obj.addTerm(dropped[p], droppingPenalty);
            }
        }
        model.minimize(obj);

        if (warmStart != null) applyWarmStart1D(model, x, s);

        CpSatParams.Extractor<CuttingSolution> extract = (v, status, runtime) -> {
            List<StockAssignment> assignments = new ArrayList<>();
            List<String> unplacedPieces = new ArrayList<>();

            if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
                // Group cut pieces by roll
                Map<Integer, List<CutPiece>> piecesByRoll = new LinkedHashMap<>();
                for (int r = 0; r < R; r++) {
                    piecesByRoll.put(r, new ArrayList<>());
                }
                for (int p = 0; p < P; p++) {
                    int assignedRoll = -1;
                    for (int r = 0; r < R; r++) {
                        if (v.booleanValue(x[p][r])) {
                            assignedRoll = r;
                            break;
                        }
                    }
                    if (assignedRoll >= 0) {
                        long startVal = v.value(s[p][assignedRoll]);
                        piecesByRoll.get(assignedRoll).add(
                                new CutPiece(pieces.get(p).id(), pieces.get(p).length(), startVal, 0L, 0));
                    } else {
                        unplacedPieces.add(pieces.get(p).id());
                    }
                }

                for (int r = 0; r < R; r++) {
                    StockDef roll = stocks.get(r);
                    List<CutPiece> cuts = piecesByRoll.get(r);
                    long cutLength = cuts.stream().mapToLong(CutPiece::length).sum();
                    long waste = cuts.isEmpty() ? 0L : roll.length() - cutLength;
                    assignments.add(new StockAssignment(roll.id(), roll.length(), 0L, roll.cost(),
                            List.copyOf(cuts), waste));
                }
            }

            OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                    ? OptionalDouble.of(v.objectiveValue())
                    : OptionalDouble.empty();

            return new CuttingSolution(status, objValue, runtime, assignments, unplacedPieces);
        };
        return params.solve(model, extract, listener);
    }

    // ── 2D non-guillotine path ────────────────────────────────────────────────

    private CuttingSolution solve2D() {
        // In 2D, every stock and every piece must declare a width.
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

        int P = pieces.size();
        int R = stocks.size();

        CpModel model = new CpModel();

        // x[p][r] = 1 iff piece p is cut from sheet r
        BoolVar[][] x = new BoolVar[P][R];
        // X axis (length): sx/ex/ix only created when the piece fits on the sheet
        IntVar[][] sx = new IntVar[P][R];
        IntVar[][] ex = new IntVar[P][R];
        IntervalVar[][] ix = new IntervalVar[P][R];
        // Y axis (width): sy/ey/iy
        IntVar[][] sy = new IntVar[P][R];
        IntVar[][] ey = new IntVar[P][R];
        IntervalVar[][] iy = new IntervalVar[P][R];

        for (int p = 0; p < P; p++) {
            PieceDef piece = pieces.get(p);
            for (int r = 0; r < R; r++) {
                StockDef sheet = stocks.get(r);
                x[p][r] = model.newBoolVar("x_" + piece.id() + "_" + sheet.id());
                boolean fits = piece.length() <= sheet.length() && piece.width() <= sheet.width();
                if (fits) {
                    sx[p][r] = model.newIntVar(0, sheet.length() - piece.length(),
                            "sx_" + piece.id() + "_" + sheet.id());
                    ex[p][r] = model.newIntVar(0, sheet.length(),
                            "ex_" + piece.id() + "_" + sheet.id());
                    model.addEquality(ex[p][r],
                            LinearExpr.sum(new IntVar[]{sx[p][r], model.newConstant(piece.length())}));
                    ix[p][r] = model.newOptionalIntervalVar(sx[p][r], model.newConstant(piece.length()),
                            ex[p][r], x[p][r], "ix_" + piece.id() + "_" + sheet.id());

                    sy[p][r] = model.newIntVar(0, sheet.width() - piece.width(),
                            "sy_" + piece.id() + "_" + sheet.id());
                    ey[p][r] = model.newIntVar(0, sheet.width(),
                            "ey_" + piece.id() + "_" + sheet.id());
                    model.addEquality(ey[p][r],
                            LinearExpr.sum(new IntVar[]{sy[p][r], model.newConstant(piece.width())}));
                    iy[p][r] = model.newOptionalIntervalVar(sy[p][r], model.newConstant(piece.width()),
                            ey[p][r], x[p][r], "iy_" + piece.id() + "_" + sheet.id());
                } else {
                    // Piece does not fit on this sheet in either orientation: forbid it.
                    model.addEquality(x[p][r], 0);
                }
            }
        }

        // Each piece is assigned to at most one sheet (exactly one when dropping is off).
        BoolVar[] dropped = null;
        if (droppingPenalty > 0) {
            dropped = new BoolVar[P];
        }
        for (int p = 0; p < P; p++) {
            BoolVar[] row = new BoolVar[R];
            System.arraycopy(x[p], 0, row, 0, R);
            if (droppingPenalty > 0) {
                dropped[p] = model.newBoolVar("dropped_" + pieces.get(p).id());
                BoolVar[] rowWithDrop = new BoolVar[R + 1];
                System.arraycopy(x[p], 0, rowWithDrop, 0, R);
                rowWithDrop[R] = dropped[p];
                model.addEquality(buildWeightedSum(rowWithDrop, ones(R + 1)), 1);
                model.addLessOrEqual(buildWeightedSum(row, ones(R)), 1);
            } else {
                model.addEquality(buildWeightedSum(row, ones(R)), 1);
            }
        }

        // used[r] = 1 iff sheet r is consumed
        BoolVar[] used = new BoolVar[R];
        for (int r = 0; r < R; r++) {
            used[r] = model.newBoolVar("used_" + stocks.get(r).id());
        }

        // Per-sheet pairwise 2D no-overlap + link x[p][r] <= used[r].
        for (int r = 0; r < R; r++) {
            for (int p = 0; p < P; p++) {
                if (ix[p][r] != null) {
                    model.addLessOrEqual(x[p][r], used[r]);
                }
            }
            for (int p = 0; p < P; p++) {
                if (ix[p][r] == null) continue;
                for (int q = p + 1; q < P; q++) {
                    if (ix[q][r] == null) continue;
                    BoolVar left  = model.newBoolVar("lx_" + p + "_" + q + "_" + r); // p before q on X
                    BoolVar right = model.newBoolVar("rx_" + p + "_" + q + "_" + r); // q before p on X
                    BoolVar front = model.newBoolVar("ly_" + p + "_" + q + "_" + r); // p before q on Y
                    BoolVar back  = model.newBoolVar("ry_" + p + "_" + q + "_" + r); // q before p on Y

                    model.addLessOrEqual(ex[p][r], sx[q][r]).onlyEnforceIf(left);
                    model.addLessOrEqual(ex[q][r], sx[p][r]).onlyEnforceIf(right);
                    model.addLessOrEqual(ey[p][r], sy[q][r]).onlyEnforceIf(front);
                    model.addLessOrEqual(ey[q][r], sy[p][r]).onlyEnforceIf(back);

                    // When both pieces are on sheet r, at least one separation must hold.
                    model.addBoolOr(new Literal[]{left, right, front, back})
                            .onlyEnforceIf(new Literal[]{x[p][r], x[q][r]});
                }
            }
        }

        // Objective — dimension-aware.
        LinearExprBuilder obj = LinearExpr.newBuilder();
        switch (goal) {
            case CuttingGoal.MinimizeWaste minimizeWaste -> {
                // In 2D minimize total AREA consumed (sum of used sheet area); total piece
                // area is a constant, so this is equivalent to minimizing area waste.
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], stocks.get(r).length() * stocks.get(r).width());
                }
            }
            case CuttingGoal.MinimizeStockUsed minimizeStockUsed -> {
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], 1);
                }
            }
            case CuttingGoal.MinimizeTotalCost minimizeTotalCost -> {
                for (int r = 0; r < R; r++) {
                    obj.addTerm(used[r], stocks.get(r).cost());
                }
            }
            case null, default -> throw new ModelException("Unsupported cutting goal: " + goal);
        }
        if (droppingPenalty > 0) {
            for (int p = 0; p < P; p++) {
                obj.addTerm(dropped[p], droppingPenalty);
            }
        }
        model.minimize(obj);

        if (warmStart != null) applyWarmStart2D(model, x, sx, sy);

        CpSatParams.Extractor<CuttingSolution> extract = (v, status, runtime) -> {
            List<StockAssignment> assignments = new ArrayList<>();
            List<String> unplacedPieces = new ArrayList<>();

            if (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE) {
                Map<Integer, List<CutPiece>> piecesBySheet = new LinkedHashMap<>();
                for (int r = 0; r < R; r++) {
                    piecesBySheet.put(r, new ArrayList<>());
                }
                for (int p = 0; p < P; p++) {
                    int assignedSheet = -1;
                    for (int r = 0; r < R; r++) {
                        if (v.booleanValue(x[p][r])) {
                            assignedSheet = r;
                            break;
                        }
                    }
                    if (assignedSheet >= 0) {
                        long xVal = v.value(sx[p][assignedSheet]);
                        long yVal = v.value(sy[p][assignedSheet]);
                        piecesBySheet.get(assignedSheet).add(
                                new CutPiece(pieces.get(p).id(), pieces.get(p).length(),
                                        xVal, yVal, pieces.get(p).width()));
                    } else {
                        unplacedPieces.add(pieces.get(p).id());
                    }
                }

                for (int r = 0; r < R; r++) {
                    StockDef sheet = stocks.get(r);
                    List<CutPiece> cuts = piecesBySheet.get(r);
                    long cutArea = cuts.stream().mapToLong(c -> c.length() * c.width()).sum();
                    long waste = cuts.isEmpty() ? 0L : sheet.length() * sheet.width() - cutArea;
                    assignments.add(new StockAssignment(sheet.id(), sheet.length(), sheet.width(),
                            sheet.cost(), List.copyOf(cuts), waste));
                }
            }

            OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                    ? OptionalDouble.of(v.objectiveValue())
                    : OptionalDouble.empty();

            return new CuttingSolution(status, objValue, runtime, assignments, unplacedPieces);
        };
        return params.solve(model, extract, listener);
    }

    /** Hints roll choice and offset for every piece placed in a previous 1D solution. */
    private void applyWarmStart1D(CpModel model, BoolVar[][] x, IntVar[][] s) {
        forEachPreviousPlacement((p, r, cut) -> {
            for (int rr = 0; rr < stocks.size(); rr++) model.addHint(x[p][rr], rr == r ? 1 : 0);
            if (s[p][r] != null) model.addHint(s[p][r], cut.x());
        });
    }

    /** Hints sheet choice and X/Y origin for every piece placed in a previous 2D solution. */
    private void applyWarmStart2D(CpModel model, BoolVar[][] x, IntVar[][] sx, IntVar[][] sy) {
        forEachPreviousPlacement((p, r, cut) -> {
            for (int rr = 0; rr < stocks.size(); rr++) model.addHint(x[p][rr], rr == r ? 1 : 0);
            if (sx[p][r] != null) model.addHint(sx[p][r], cut.x());
            if (sy[p][r] != null) model.addHint(sy[p][r], cut.y());
        });
    }

    private interface PlacementVisitor { void visit(int pieceIdx, int stockIdx, CutPiece cut); }

    private void forEachPreviousPlacement(PlacementVisitor visitor) {
        Map<String, Integer> pieceIdx = new HashMap<>();
        for (int p = 0; p < pieces.size(); p++) pieceIdx.put(pieces.get(p).id(), p);
        Map<String, Integer> stockIdx = new HashMap<>();
        for (int r = 0; r < stocks.size(); r++) stockIdx.put(stocks.get(r).id(), r);
        for (StockAssignment a : warmStart.assignments()) {
            Integer r = stockIdx.get(a.stockId());
            if (r == null) continue;
            for (CutPiece cut : a.pieces()) {
                Integer p = pieceIdx.get(cut.pieceId());
                if (p != null) visitor.visit(p, r, cut);
            }
        }
    }

    private void validate() {
        if (stocks.isEmpty()) throw new ModelException("At least one stock roll is required.");
        if (pieces.isEmpty()) throw new ModelException("At least one piece is required.");
        if (goal == null) throw new ModelException("Cutting goal must be set.");
    }

    private static long[] ones(int n) {
        long[] c = new long[n];
        Arrays.fill(c, 1L);
        return c;
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

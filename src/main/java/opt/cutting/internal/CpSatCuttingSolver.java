package opt.cutting.internal;

import com.google.ortools.sat.*;
import opt.ModelException;
import opt.SolveStatus;
import opt.cutting.CuttingGoal;
import opt.cutting.CuttingSolution;
import opt.cutting.StockAssignment;
import opt.cutting.StockAssignment.CutPiece;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a 1D cutting-stock model into CP-SAT and solves it.
 *
 * <p>Each stock roll is treated as a 1D "machine" and each piece as an optional interval
 * on that roll. A boolean {@code x[p][r]} selects whether piece {@code p} is cut from roll
 * {@code r}; the optional interval {@code iv[p][r]} places the piece at a start offset,
 * and per-roll {@code NoOverlap} guarantees that cuts on the same roll do not overlap.
 * Every piece is required (assigned to exactly one roll).
 */
public final class CpSatCuttingSolver {

    private final List<StockDef> stocks;
    private final List<PieceDef> pieces;
    private final CuttingGoal goal;

    private long timeLimitSeconds = 60;
    private int workers = 1;
    private long seed = 0;
    private boolean verbose = false;

    /**
     * Creates a solver for the given expanded rolls, piece instances, and objective.
     * Internal; obtain instances via {@link opt.cutting.CuttingBuilder#solver()}.
     */
    public CpSatCuttingSolver(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal) {
        this.stocks = List.copyOf(stocks);
        this.pieces = List.copyOf(pieces);
        this.goal = goal;
    }

    /** Sets the maximum wall-clock time the solver may run before giving up. */
    public CpSatCuttingSolver timeLimit(Duration limit) { this.timeLimitSeconds = limit.toSeconds(); return this; }
    /** Sets the number of parallel search workers (threads). */
    public CpSatCuttingSolver workers(int n)            { this.workers = n;                            return this; }
    /** Sets the random seed used to make search reproducible. */
    public CpSatCuttingSolver seed(long s)              { this.seed = s;                               return this; }
    /** Enables or disables logging of search progress to standard output. */
    public CpSatCuttingSolver verbose(boolean v)        { this.verbose = v;                            return this; }

    /**
     * Builds the CP-SAT model, solves it, and maps the result back to roll/piece
     * identifiers. Returns one {@link StockAssignment} per declared roll (empty rolls
     * included, in declaration order) and lists piece instances that could not be placed.
     */
    public CuttingSolution solve() {
        validate();

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

        // Each piece is REQUIRED: assigned to exactly one roll.
        for (int p = 0; p < P; p++) {
            BoolVar[] row = new BoolVar[R];
            System.arraycopy(x[p], 0, row, 0, R);
            model.addEquality(buildWeightedSum(row, ones(R)), 1);
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
            case null, default -> throw new ModelException("Unsupported cutting goal: " + goal);
        }
        model.minimize(obj);

        // Solve
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(workers);
        solver.getParameters().setRandomSeed((int) seed);
        solver.getParameters().setLogSearchProgress(verbose);

        long startMs = System.currentTimeMillis();
        CpSolverStatus cpStatus = solver.solve(model);
        Duration runtime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        SolveStatus status =  SolveStatus.mapStatus(cpStatus);
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
                    if (solver.booleanValue(x[p][r])) {
                        assignedRoll = r;
                        break;
                    }
                }
                if (assignedRoll >= 0) {
                    long startVal = solver.value(s[p][assignedRoll]);
                    piecesByRoll.get(assignedRoll).add(
                            new CutPiece(pieces.get(p).id(), pieces.get(p).length(), startVal));
                } else {
                    unplacedPieces.add(pieces.get(p).id());
                }
            }

            for (int r = 0; r < R; r++) {
                StockDef roll = stocks.get(r);
                List<CutPiece> cuts = piecesByRoll.get(r);
                long cutLength = cuts.stream().mapToLong(CutPiece::length).sum();
                long waste = cuts.isEmpty() ? 0L : roll.length() - cutLength;
                assignments.add(new StockAssignment(roll.id(), roll.length(), roll.cost(),
                        List.copyOf(cuts), waste));
            }
        }

        OptionalDouble objValue = (status == SolveStatus.OPTIMAL || status == SolveStatus.FEASIBLE)
                ? OptionalDouble.of(solver.objectiveValue())
                : OptionalDouble.empty();

        return new CuttingSolution(status, objValue, runtime, assignments, unplacedPieces);
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

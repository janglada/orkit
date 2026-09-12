package opt.cutting;

import opt.ModelException;
import opt.CpSatProblem;
import opt.CpSatSolver;
import opt.internal.AbstractCpSatSolver;
import opt.cutting.internal.CpSatCuttingSolver;
import opt.cutting.internal.PieceDef;
import opt.cutting.internal.StockDef;
import opt.internal.Validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for cutting-stock problems (1D or 2D non-guillotine). Entry point: {@code Optimize.cuttingStock()}. */
public final class CuttingBuilder implements CpSatProblem<CuttingSolution> {

    private final List<StockConfig> rawStocks = new ArrayList<>();
    private final List<PieceConfig> rawPieces = new ArrayList<>();
    private final Set<String> stockIds = new HashSet<>();
    private final Set<String> pieceIds = new HashSet<>();
    private CuttingGoal goal = Goals.minimizeWaste();
    private long droppingPenalty = 0;

    /** Declares a stock roll type with the given unique id and configures it via the {@link StockConfig} lambda. */
    public CuttingBuilder stock(String id, Consumer<StockConfig> config) {
        Validate.nonBlank(id, "stock id");
        if (!stockIds.add(id)) {
            throw new ModelException("Duplicate stock id: '" + id + "'.");
        }
        StockConfig cfg = new StockConfig();
        cfg.id = id;
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Stock '" + id + "' must set a length via .length(...).");
        }
        rawStocks.add(cfg);
        return this;
    }

    /** Declares a required piece type with the given unique id and configures it via the {@link PieceConfig} lambda. */
    public CuttingBuilder required(String id, Consumer<PieceConfig> config) {
        Validate.nonBlank(id, "piece id");
        if (!pieceIds.add(id)) {
            throw new ModelException("Duplicate piece id: '" + id + "'.");
        }
        PieceConfig cfg = new PieceConfig();
        cfg.id = id;
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Piece '" + id + "' must set a length via .length(...).");
        }
        rawPieces.add(cfg);
        return this;
    }

    /** Sets the minimization objective. Defaults to {@link Goals#minimizeWaste()}. */
    public CuttingBuilder goal(CuttingGoal goal) {
        if (goal == null) throw new ModelException("goal() must not be null.");
        this.goal = goal;
        return this;
    }

    /**
     * Allows pieces to be dropped (left uncut) from the solution. Each dropped piece
     * adds {@code penalty} to the objective, so the solver only drops a piece when it
     * is cheaper than placing it. A penalty of 0 or negative disables dropping
     * (pieces are required, as before).
     */
    public CuttingBuilder allowDroppingPieces(long penalty) {
        this.droppingPenalty = penalty;
        return this;
    }

    /** Builds an immutable, reusable problem instance with quantity expansion applied. */
    public CpSatProblem<CuttingSolution> build() {
        if (rawStocks.isEmpty()) {
            throw new ModelException("At least one stock roll is required.");
        }
        if (rawPieces.isEmpty()) {
            throw new ModelException("At least one piece is required.");
        }

        // Quantity expansion: instance ids are "<declared id>#<n>" with n counting from 1
        // per type, so results map straight back to the caller's ids (see StockAssignment.stockType()).
        List<StockDef> stockDefs = new ArrayList<>();
        for (StockConfig sc : rawStocks) {
            for (int q = 1; q <= sc.quantity(); q++) {
                stockDefs.add(new StockDef(sc.id() + "#" + q, sc.length(), sc.width(), sc.cost()));
            }
        }

        List<PieceDef> pieceDefs = new ArrayList<>();
        for (PieceConfig pc : rawPieces) {
            for (int q = 1; q <= pc.quantity(); q++) {
                pieceDefs.add(new PieceDef(pc.id() + "#" + q, pc.length(), pc.width()));
            }
        }

        return new CuttingProblem(List.copyOf(stockDefs), List.copyOf(pieceDefs), goal, droppingPenalty);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<CuttingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class CuttingProblem implements CpSatProblem<CuttingSolution> {

        private final List<StockDef> stocks;
        private final List<PieceDef> pieces;
        private final CuttingGoal goal;
        private final long droppingPenalty;

        CuttingProblem(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal, long droppingPenalty) {
            this.stocks = stocks;
            this.pieces = pieces;
            this.goal = goal;
            this.droppingPenalty = droppingPenalty;
        }

        @Override
        public CpSatSolver<CuttingSolution> solver() {
            return new CuttingSolver(stocks, pieces, goal, droppingPenalty);
        }
    }

    private static final class CuttingSolver extends AbstractCpSatSolver<CuttingSolution> {

        private final List<StockDef> stocks;
        private final List<PieceDef> pieces;
        private final CuttingGoal goal;
        private final long droppingPenalty;

        CuttingSolver(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal, long droppingPenalty) {
            this.stocks = stocks;
            this.pieces = pieces;
            this.goal = goal;
            this.droppingPenalty = droppingPenalty;
        }

        @Override
        public CuttingSolution solve() {
            return new CpSatCuttingSolver(stocks, pieces, goal, droppingPenalty, params, listener, warmStart).solve();
        }
    }
}

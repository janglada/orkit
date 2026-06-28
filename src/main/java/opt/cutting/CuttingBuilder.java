package opt.cutting;

import com.google.ortools.Loader;
import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.cutting.internal.CpSatCuttingSolver;
import opt.cutting.internal.PieceDef;
import opt.cutting.internal.StockDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for 1D cutting-stock problems. Entry point: {@code Optimize.cuttingStock()}. */
public final class CuttingBuilder implements OptimizationProblem<CuttingSolution> {

    static {
        Loader.loadNativeLibraries();
    }

    private final List<StockConfig> rawStocks = new ArrayList<>();
    private final List<PieceConfig> rawPieces = new ArrayList<>();
    private final Set<String> stockIds = new HashSet<>();
    private final Set<String> pieceIds = new HashSet<>();
    private CuttingGoal goal = Goals.minimizeWaste();

    /** Declares a stock roll type with the given unique id and configures it via the {@link StockConfig} lambda. */
    public CuttingBuilder stock(String id, Consumer<StockConfig> config) {
        if (!stockIds.add(id)) {
            throw new ModelException("Duplicate stock id: '" + id + "'.");
        }
        StockConfig cfg = new StockConfig();
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Stock '" + id + "' must set a length via .length(...).");
        }
        rawStocks.add(cfg);
        return this;
    }

    /** Declares a required piece type with the given unique id and configures it via the {@link PieceConfig} lambda. */
    public CuttingBuilder required(String id, Consumer<PieceConfig> config) {
        if (!pieceIds.add(id)) {
            throw new ModelException("Duplicate piece id: '" + id + "'.");
        }
        PieceConfig cfg = new PieceConfig();
        config.accept(cfg);
        if (cfg.length() < 1) {
            throw new ModelException("Piece '" + id + "' must set a length via .length(...).");
        }
        rawPieces.add(cfg);
        return this;
    }

    /** Sets the minimization objective. Defaults to {@link Goals#minimizeWaste()}. */
    public CuttingBuilder minimize(CuttingGoal goal) {
        if (goal == null) throw new ModelException("Cutting goal must not be null.");
        this.goal = goal;
        return this;
    }

    /** Builds an immutable, reusable problem instance with quantity expansion applied. */
    public OptimizationProblem<CuttingSolution> build() {
        if (rawStocks.isEmpty()) {
            throw new ModelException("At least one stock roll is required.");
        }
        if (rawPieces.isEmpty()) {
            throw new ModelException("At least one piece is required.");
        }

        List<StockDef> stockDefs = new ArrayList<>();
        int stockCounter = 0;
        for (StockConfig sc : rawStocks) {
            for (int q = 0; q < sc.quantity(); q++) {
                stockCounter++;
                stockDefs.add(new StockDef(sc.length() + "#" + stockCounter, sc.length(), sc.cost()));
            }
        }

        List<PieceDef> pieceDefs = new ArrayList<>();
        int pieceCounter = 0;
        for (PieceConfig pc : rawPieces) {
            for (int q = 0; q < pc.quantity(); q++) {
                pieceCounter++;
                pieceDefs.add(new PieceDef(pc.length() + "#" + pieceCounter, pc.length()));
            }
        }

        return new CuttingProblem(List.copyOf(stockDefs), List.copyOf(pieceDefs), goal);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public Solver<CuttingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class CuttingProblem implements OptimizationProblem<CuttingSolution> {

        private final List<StockDef> stocks;
        private final List<PieceDef> pieces;
        private final CuttingGoal goal;

        CuttingProblem(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal) {
            this.stocks = stocks;
            this.pieces = pieces;
            this.goal = goal;
        }

        @Override
        public Solver<CuttingSolution> solver() {
            return new CuttingSolver(stocks, pieces, goal);
        }
    }

    private static final class CuttingSolver implements Solver<CuttingSolution> {

        private final List<StockDef> stocks;
        private final List<PieceDef> pieces;
        private final CuttingGoal goal;
        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        CuttingSolver(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal) {
            this.stocks = stocks;
            this.pieces = pieces;
            this.goal = goal;
        }

        @Override public Solver<CuttingSolution> timeLimit(Duration limit) { timeLimit = limit; return this; }
        @Override public Solver<CuttingSolution> workers(int threads)       { workers = threads;  return this; }
        @Override public Solver<CuttingSolution> seed(long randomSeed)      { seed = randomSeed;  return this; }
        @Override public Solver<CuttingSolution> verbose(boolean log)       { verbose = log;      return this; }

        @Override
        public CuttingSolution solve() {
            return new CpSatCuttingSolver(stocks, pieces, goal)
                    .timeLimit(timeLimit)
                    .workers(workers)
                    .seed(seed)
                    .verbose(verbose)
                    .solve();
        }
    }
}

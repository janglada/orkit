package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.CpSatProblem;
import io.github.janglada.orkit.CpSatSolver;
import io.github.janglada.orkit.internal.AbstractCpSatSolver;
import io.github.janglada.orkit.cutting.internal.CpSatCuttingSolver;
import io.github.janglada.orkit.cutting.internal.PieceDef;
import io.github.janglada.orkit.cutting.internal.StockDef;
import io.github.janglada.orkit.internal.Validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for cutting-stock problems (1D, or 2D free or guillotine). Entry point: {@code Optimize.cuttingStock()}. */
public final class CuttingBuilder implements CpSatProblem<CuttingSolution> {

    private final List<StockConfig> rawStocks = new ArrayList<>();
    private final List<PieceConfig> rawPieces = new ArrayList<>();
    private final Set<String> stockIds = new HashSet<>();
    private final Set<String> pieceIds = new HashSet<>();
    private CuttingGoal goal = Goals.minimizeWaste();
    private long droppingPenalty = 0;
    private long kerf = 0;
    private boolean guillotine = false;

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

    /**
     * Sets the blade width lost at every cut; default 0. Pieces on one roll are separated by
     * {@code kerf} (and, in 2D, along both axes), while a piece may touch the stock edge. The
     * lost material counts as waste.
     *
     * @throws ModelException if {@code kerf} is negative
     */
    public CuttingBuilder kerf(long kerf) {
        Validate.nonNegative(kerf, "kerf");
        this.kerf = kerf;
        return this;
    }

    /**
     * Restricts 2D layouts to guillotine cuts using the two-stage shelf form: each sheet is
     * cut across its width into shelves, each shelf as tall as its widest piece, and each shelf
     * is cut along its length into pieces (with a trim cut where a piece is narrower than its
     * shelf). Every such layout can be produced by edge-to-edge cuts. Free-form multi-stage
     * guillotine patterns are not modeled, and rotatable pieces are rejected. Ignored in 1D,
     * where every layout is guillotine.
     */
    public CuttingBuilder guillotine(boolean guillotine) {
        this.guillotine = guillotine;
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
                stockDefs.add(new StockDef(sc.id() + "#" + q, sc.id(), sc.length(), sc.width(), sc.cost()));
            }
        }

        List<PieceDef> pieceDefs = new ArrayList<>();
        for (PieceConfig pc : rawPieces) {
            pieceDefs.add(new PieceDef(pc.id(), pc.length(), pc.width(), pc.quantity(), pc.rotatable));
        }
        boolean is2D = rawStocks.stream().anyMatch(sc -> sc.width() > 0) || rawPieces.stream().anyMatch(pc -> pc.width() > 0);
        for (PieceConfig pc : rawPieces) {
            if (pc.rotatable && !is2D) {
                throw new ModelException("Piece '" + pc.id() + "' is rotatable, but rotation needs a 2D model (declare widths).");
            }
            if (pc.rotatable && guillotine) {
                throw new ModelException("Piece '" + pc.id() + "' is rotatable; guillotine(true) does not support rotation.");
            }
        }

        Spec spec = new Spec(List.copyOf(stockDefs), List.copyOf(pieceDefs), goal, droppingPenalty, kerf, guillotine && is2D);
        return new CuttingProblem(spec);
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<CuttingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    /** Everything a solve needs; immutable. */
    private record Spec(List<StockDef> stocks, List<PieceDef> pieces, CuttingGoal goal, long droppingPenalty,
                        long kerf, boolean guillotine) {}

    private static final class CuttingProblem implements CpSatProblem<CuttingSolution> {

        private final Spec spec;

        CuttingProblem(Spec spec) {
            this.spec = spec;
        }

        @Override
        public CpSatSolver<CuttingSolution> solver() {
            return new CuttingSolver(spec);
        }
    }

    private static final class CuttingSolver extends AbstractCpSatSolver<CuttingSolution> {

        private final Spec spec;

        CuttingSolver(Spec spec) {
            this.spec = spec;
        }

        @Override
        protected CuttingSolution doSolve() {
            return new CpSatCuttingSolver(spec.stocks(), spec.pieces(), spec.goal(), spec.droppingPenalty(),
                    spec.kerf(), spec.guillotine(), params, listener, warmStart).solve();
        }
    }
}

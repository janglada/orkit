package io.github.janglada.orkit.cutting;

import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/** The solved result of a cutting-stock problem (1D, or 2D free or guillotine). */
public final class CuttingSolution implements Solution {

    private final SolveStatus status;
    private final SolveDiagnostics diagnostics;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<StockAssignment> assignments;
    private final List<String> unplacedPieces;

    /** Creates a result without search statistics ({@link SolveDiagnostics#none()}). */
    public CuttingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<StockAssignment> assignments, List<String> unplacedPieces) {
        this(status, objective, runtime, assignments, unplacedPieces, SolveDiagnostics.none());
    }

    /** Creates a result with the given search statistics. */
    public CuttingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<StockAssignment> assignments, List<String> unplacedPieces,
                           SolveDiagnostics diagnostics) {
        this.status = status;
        this.diagnostics = (diagnostics == null) ? SolveDiagnostics.none() : diagnostics;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(assignments);
        this.unplacedPieces = Collections.unmodifiableList(unplacedPieces);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }
    /** Search statistics reported by CP-SAT for this result. */
    @Override public SolveDiagnostics diagnostics() { return diagnostics; }

    /** One {@link StockAssignment} per declared roll, in declaration order (empty rolls included). */
    public List<StockAssignment> assignments() {
        return assignments;
    }

    /** Piece identifiers that were not placed on any roll. Empty when every piece fits. */
    public List<String> unplacedPieces() {
        return unplacedPieces;
    }

    /** The number of rolls that actually hold at least one piece. */
    public long totalStockUsed() {
        return assignments.stream().filter(a -> !a.pieces().isEmpty()).count();
    }

    /** The total trim waste across all used rolls (sum of {@link StockAssignment#waste()}). */
    public long totalWaste() {
        return assignments.stream()
                .filter(a -> !a.pieces().isEmpty())
                .mapToLong(StockAssignment::waste)
                .sum();
    }

    /**
     * The total area waste across all used 2D sheets (sum of {@link StockAssignment#waste()}
     * for sheets with {@code width > 0}). Returns {@code 0} for a 1D model.
     */
    public long totalAreaWaste() {
        return assignments.stream()
                .filter(a -> !a.pieces().isEmpty() && a.width() > 0)
                .mapToLong(StockAssignment::waste)
                .sum();
    }
}

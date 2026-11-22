package io.github.janglada.orkit.internal;

import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.Literal;

/**
 * Read access to solved variable values, backed either by a finished {@link CpSolver}
 * or by a {@link CpSolverSolutionCallback} during the search. Lets one extraction
 * routine serve both final and intermediate solutions.
 */
public interface CpValues {

    /** Value of an integer expression in the current solution. */
    long value(LinearArgument expr);

    /** Value of a literal in the current solution. */
    boolean booleanValue(Literal literal);

    /** Objective value of the current solution. */
    double objectiveValue();

    /** Best objective bound proven so far. */
    double bestObjectiveBound();

    /** Search branches explored so far. */
    long numBranches();

    /** Conflicts encountered so far. */
    long numConflicts();

    /** Values read from a finished solver. */
    static CpValues of(CpSolver solver) {
        return new CpValues() {
            @Override public long value(LinearArgument expr)      { return solver.value(expr); }
            @Override public boolean booleanValue(Literal literal) { return solver.booleanValue(literal); }
            @Override public double objectiveValue()               { return solver.objectiveValue(); }
            @Override public double bestObjectiveBound()           { return solver.bestObjectiveBound(); }
            @Override public long numBranches()                    { return solver.numBranches(); }
            @Override public long numConflicts()                   { return solver.numConflicts(); }
        };
    }

    /** Values read from a solution callback while the search is running. */
    static CpValues of(CpSolverSolutionCallback callback) {
        return new CpValues() {
            @Override public long value(LinearArgument expr)      { return callback.value(expr); }
            @Override public boolean booleanValue(Literal literal) { return callback.booleanValue(literal); }
            @Override public double objectiveValue()               { return callback.objectiveValue(); }
            @Override public double bestObjectiveBound()           { return callback.bestObjectiveBound(); }
            @Override public long numBranches()                    { return callback.numBranches(); }
            @Override public long numConflicts()                   { return callback.numConflicts(); }
        };
    }
}

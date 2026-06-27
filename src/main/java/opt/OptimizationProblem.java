package opt;

public interface OptimizationProblem<S extends Solution> {

    Solver<S> solver();
}

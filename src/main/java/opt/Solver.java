package opt;

import java.time.Duration;

public interface Solver<S extends Solution> {

    Solver<S> timeLimit(Duration limit);

    Solver<S> workers(int threads);

    Solver<S> seed(long randomSeed);

    Solver<S> verbose(boolean logToStdout);

    S solve();
}

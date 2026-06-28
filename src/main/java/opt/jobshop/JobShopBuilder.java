package opt.jobshop;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.jobshop.internal.CpSatJobShopSolver;
import opt.jobshop.internal.JobDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for job shop problems. Entry point: {@code Optimize.jobShop()}. */
public final class JobShopBuilder implements OptimizationProblem<JobShopSchedule> {

    private final Set<String> machines = new LinkedHashSet<>();
    private final List<JobDef> jobs = new ArrayList<>();

    /** Registers one or more machines that operations can be scheduled on. */
    public JobShopBuilder machines(String... machineIds) {
        machines.addAll(Arrays.asList(machineIds));
        return this;
    }

    /**
     * Adds a job to the schedule, configuring its ordered operations inside the
     * supplied lambda via {@link JobConfig}.
     */
    public JobShopBuilder job(String id, Consumer<JobConfig> config) {
        JobConfig cfg = new JobConfig();
        config.accept(cfg);
        cfg.commitPending();
        if (cfg.committed.isEmpty()) {
            throw new ModelException("Job '" + id + "' must have at least one operation.");
        }
        jobs.add(new JobDef(id, List.copyOf(cfg.committed)));
        return this;
    }

    /** Builds an immutable, reusable problem instance (minimizes makespan). */
    public OptimizationProblem<JobShopSchedule> build() {
        return new JobShopProblem(List.copyOf(machines), List.copyOf(jobs));
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public Solver<JobShopSchedule> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class JobShopProblem implements OptimizationProblem<JobShopSchedule> {

        private final List<String> machines;
        private final List<JobDef> jobs;

        JobShopProblem(List<String> machines, List<JobDef> jobs) {
            this.machines = machines;
            this.jobs = jobs;
        }

        @Override
        public Solver<JobShopSchedule> solver() {
            return new JobShopSolver(machines, jobs);
        }
    }

    private static final class JobShopSolver implements Solver<JobShopSchedule> {

        private final List<String> machines;
        private final List<JobDef> jobs;
        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        JobShopSolver(List<String> machines, List<JobDef> jobs) {
            this.machines = machines;
            this.jobs = jobs;
        }

        @Override public Solver<JobShopSchedule> timeLimit(Duration limit) { timeLimit = limit; return this; }
        @Override public Solver<JobShopSchedule> workers(int threads)       { workers = threads;  return this; }
        @Override public Solver<JobShopSchedule> seed(long randomSeed)      { seed = randomSeed;  return this; }
        @Override public Solver<JobShopSchedule> verbose(boolean log)       { verbose = log;      return this; }

        @Override
        public JobShopSchedule solve() {
            return new CpSatJobShopSolver(machines, jobs)
                    .timeLimit(timeLimit)
                    .workers(workers)
                    .seed(seed)
                    .verbose(verbose)
                    .solve();
        }
    }
}

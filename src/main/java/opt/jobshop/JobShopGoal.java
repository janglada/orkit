package opt.jobshop;

import opt.internal.Validate;

/**
 * Objective for a job-shop problem. Create instances via {@link Goals}.
 *
 * <p>Hard release dates / deadlines (set per operation via
 * {@link OperationConfig#releaseAt(long)} / {@link OperationConfig#deadlineAt(long)})
 * are enforced as constraints regardless of the selected goal. The goal only
 * selects the objective expression; soft due dates
 * ({@link OperationConfig#dueBy(long)}) take effect under a goal that includes
 * tardiness.
 */
public sealed interface JobShopGoal
        permits JobShopGoal.MinimizeMakespan, JobShopGoal.MinimizeTardiness,
                JobShopGoal.MinimizeMakespanAndTardiness {

    /** Minimize the makespan (time the last operation ends). This is the default. */
    record MinimizeMakespan() implements JobShopGoal {}

    /**
     * Minimize the weighted sum of operation tardiness
     * ({@code weight * sum(max(0, end - due))}) over operations that carry a
     * {@link OperationConfig#dueBy(long) due date}. Makespan is not in the
     * objective.
     *
     * @param weight per-unit-time tardiness penalty; must be {@code > 0}.
     */
    record MinimizeTardiness(long weight) implements JobShopGoal {
        public MinimizeTardiness {
            Validate.positive(weight, "tardiness weight");
        }
    }

    /**
     * Minimize {@code makespanWeight * makespan + tardinessWeight * totalTardiness}.
     *
     * @param makespanWeight    weight on the makespan; must be {@code > 0}.
     * @param tardinessWeight   per-unit-time tardiness penalty; must be {@code > 0}.
     */
    record MinimizeMakespanAndTardiness(long makespanWeight, long tardinessWeight) implements JobShopGoal {
        public MinimizeMakespanAndTardiness {
            Validate.positive(makespanWeight, "makespan weight");
            Validate.positive(tardinessWeight, "tardiness weight");
        }
    }
}

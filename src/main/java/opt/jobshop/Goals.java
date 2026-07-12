package opt.jobshop;

/** Factory for job-shop objectives. */
public final class Goals {

    /** Returns the default goal: minimize the makespan. */
    public static JobShopGoal minimizeMakespan() {
        return new JobShopGoal.MinimizeMakespan();
    }

    /**
     * Returns the goal of minimizing {@code weight} times the total operation
     * tardiness ({@code sum(max(0, end - due))} over operations with a due date).
     *
     * @param weight per-unit-time tardiness penalty; must be {@code > 0}.
     */
    public static JobShopGoal minimizeTardiness(long weight) {
        return new JobShopGoal.MinimizeTardiness(weight);
    }

    /**
     * Returns the combined goal minimizing
     * {@code makespanWeight * makespan + tardinessWeight * totalTardiness}.
     */
    public static JobShopGoal minimizeMakespanAndTardiness(long makespanWeight, long tardinessWeight) {
        return new JobShopGoal.MinimizeMakespanAndTardiness(makespanWeight, tardinessWeight);
    }

    private Goals() {}
}

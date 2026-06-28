package opt.scheduling;

import opt.ModelException;

/** Soft preference folded into the weighted objective. Create instances via {@link Goals}. */
public sealed interface SoftGoal
        permits SoftGoal.BalanceWorkloadAcrossEmployees, SoftGoal.HonorEmployeePreferences {

    /** Default penalty per minute of workload imbalance (in objective units). */
    long DEFAULT_BALANCE_WEIGHT = 10L;
    /** Default reward per satisfied employee preference (in objective units). */
    long DEFAULT_PREFERENCE_WEIGHT = 5L;

    /**
     * Penalize the spread between the most- and least-loaded employee.
     * @param weight penalty applied per minute of imbalance; must be > 0
     */
    record BalanceWorkloadAcrossEmployees(long weight) implements SoftGoal {
        public BalanceWorkloadAcrossEmployees() { this(DEFAULT_BALANCE_WEIGHT); }
        public BalanceWorkloadAcrossEmployees {
            if (weight <= 0) throw new ModelException("balance weight must be > 0, got: " + weight);
        }
    }

    /**
     * Reward (subtract from the objective) each satisfied employee shift preference.
     * @param weight reward magnitude per satisfied preference; must be > 0
     */
    record HonorEmployeePreferences(long weight) implements SoftGoal {
        public HonorEmployeePreferences() { this(DEFAULT_PREFERENCE_WEIGHT); }
        public HonorEmployeePreferences {
            if (weight <= 0) throw new ModelException("preference weight must be > 0, got: " + weight);
        }
    }
}

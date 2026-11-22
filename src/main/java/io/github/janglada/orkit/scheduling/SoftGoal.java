package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;

/** Soft preference folded into the weighted objective. Create instances via {@link Goals}. */
public sealed interface SoftGoal
        permits SoftGoal.BalanceWorkloadAcrossEmployees, SoftGoal.HonorEmployeePreferences,
                SoftGoal.PenalizeUnderstaffing, SoftGoal.PenalizeContractDeviation {

    /** Default penalty per minute of workload imbalance (in objective units). */
    long DEFAULT_BALANCE_WEIGHT = 10L;
    /** Default reward per satisfied employee preference (in objective units). */
    long DEFAULT_PREFERENCE_WEIGHT = 5L;
    /** Default penalty per missing employee on a soft-staffed shift (in objective units). */
    long DEFAULT_UNDERSTAFFING_WEIGHT = 100L;
    /** Default penalty per minute of deviation from contracted weekly hours (in objective units). */
    long DEFAULT_CONTRACT_DEVIATION_WEIGHT = 1L;

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

    /**
     * Penalize each employee missing between a shift's {@link ShiftConfig#needsAtLeast(int) minimum}
     * and its {@link ShiftConfig#needs(int) target}.
     * @param weight penalty per missing employee; must be > 0
     */
    record PenalizeUnderstaffing(long weight) implements SoftGoal {
        public PenalizeUnderstaffing() { this(DEFAULT_UNDERSTAFFING_WEIGHT); }
        public PenalizeUnderstaffing {
            if (weight <= 0) throw new ModelException("understaffing weight must be > 0, got: " + weight);
        }
    }

    /**
     * Penalize every minute an employee works above or below their
     * {@link EmployeeConfig#contractHoursPerWeek(int) contracted hours} in a full week.
     * @param weight penalty per minute of deviation; must be > 0
     */
    record PenalizeContractDeviation(long weight) implements SoftGoal {
        public PenalizeContractDeviation() { this(DEFAULT_CONTRACT_DEVIATION_WEIGHT); }
        public PenalizeContractDeviation {
            if (weight <= 0) throw new ModelException("contract deviation weight must be > 0, got: " + weight);
        }
    }
}

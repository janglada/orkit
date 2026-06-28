package opt.scheduling;

import java.time.Duration;

/** Factory for hard scheduling constraints. */
public final class Rules {

    public static Rule minRestBetweenShifts(Duration minRest) {
        return new Rule.MinRestBetweenShifts(minRest);
    }

    public static Rule maxConsecutiveWorkDays(int days) {
        return new Rule.MaxConsecutiveWorkDays(days);
    }

    public static Rule oneShiftPerEmployeePerDay() {
        return new Rule.OneShiftPerEmployeePerDay();
    }

    public static Rule maxShiftsPerWeek(int maxShifts) {
        return new Rule.MaxShiftsPerWeek(maxShifts);
    }

    public static Rule maxConsecutiveNightShifts(int maxNights) {
        return new Rule.MaxConsecutiveNightShifts(maxNights);
    }

    private Rules() {}
}

package opt.scheduling;

import java.time.Duration;

/** Hard constraint applied to the scheduling model. Create instances via {@link Rules}. */
public sealed interface Rule
        permits Rule.MinRestBetweenShifts, Rule.MaxConsecutiveWorkDays, Rule.OneShiftPerEmployeePerDay,
                  Rule.MaxShiftsPerWeek, Rule.MaxConsecutiveNightShifts {

    record MinRestBetweenShifts(Duration minRest) implements Rule {}

    record MaxConsecutiveWorkDays(int days) implements Rule {}

    record OneShiftPerEmployeePerDay() implements Rule {}

    /** At most {@code maxShifts} assignments in any rolling 7-day window per employee. */
    record MaxShiftsPerWeek(int maxShifts) implements Rule {}

    /**
     * At most {@code maxNights} night shifts in any run of {@code maxNights + 1} consecutive
     * calendar days per employee. A "night shift" is one that spans midnight ({@code to < from}).
     */
    record MaxConsecutiveNightShifts(int maxNights) implements Rule {}
}

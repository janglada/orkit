package opt.scheduling;

import java.time.Duration;

/** Hard constraint applied to the scheduling model. Create instances via {@link Rules}. */
public sealed interface Rule
        permits Rule.MinRestBetweenShifts, Rule.MaxConsecutiveWorkDays, Rule.OneShiftPerEmployeePerDay {

    record MinRestBetweenShifts(Duration minRest) implements Rule {}

    record MaxConsecutiveWorkDays(int days) implements Rule {}

    record OneShiftPerEmployeePerDay() implements Rule {}
}

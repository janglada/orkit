package opt.scheduling;

import java.time.Duration;

/** Hard constraint applied to the scheduling model. Create instances via {@link Rules}. */
public sealed interface Rule
        permits Rule.MinRestBetweenShifts, Rule.MaxConsecutiveWorkDays, Rule.OneShiftPerEmployeePerDay,
                  Rule.MaxShiftsPerWeek, Rule.MaxConsecutiveNightShifts, Rule.ForbidSequence {

    /** Minimum rest gap that must separate any two shifts worked by the same employee.
     *  @param minRest minimum duration between the end of one shift and the start of the next */
    record MinRestBetweenShifts(Duration minRest) implements Rule {}

    /** Caps the number of consecutive calendar days an employee may work.
     *  @param days maximum number of back-to-back work days; must be > 0 */
    record MaxConsecutiveWorkDays(int days) implements Rule {}

    /** Forbids an employee from being assigned to more than one shift on any single calendar day. */
    record OneShiftPerEmployeePerDay() implements Rule {}

    /** At most {@code maxShifts} assignments in any rolling 7-day window per employee.
     *  @param maxShifts maximum number of shifts permitted in any 7-day window; must be > 0 */
    record MaxShiftsPerWeek(int maxShifts) implements Rule {}

    /**
     * At most {@code maxNights} night shifts in any run of {@code maxNights + 1} consecutive
     * calendar days per employee. A "night shift" is one that spans midnight ({@code to < from}).
     * @param maxNights maximum number of consecutive night shifts; must be >= 0
     */
    record MaxConsecutiveNightShifts(int maxNights) implements Rule {}

    /**
     * Forbids an employee from working a shift of {@code toCategory} on the calendar day right
     * after a shift of {@code fromCategory}, whatever the clock times. Categories are set with
     * {@link ShiftConfig#category(String)}.
     * @param fromCategory category of the earlier shift
     * @param toCategory   category forbidden on the following day
     */
    record ForbidSequence(String fromCategory, String toCategory) implements Rule {}
}

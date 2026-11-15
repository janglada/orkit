package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;

/** Factory for hard scheduling constraints. */
public final class Rules {

    /** Creates a {@link Rule} requiring the given minimum rest gap between any two shifts of an employee. */
    public static Rule minRestBetweenShifts(Duration minRest) {
        if (minRest == null) throw new ModelException("minRestBetweenShifts must not be null.");
        return new Rule.MinRestBetweenShifts(minRest);
    }

    /** Creates a {@link Rule} capping the number of consecutive calendar days an employee may work. */
    public static Rule maxConsecutiveWorkDays(int days) {
        return new Rule.MaxConsecutiveWorkDays(days);
    }

    /** Creates a {@link Rule} forbidding an employee from working more than one shift on any calendar day. */
    public static Rule oneShiftPerEmployeePerDay() {
        return new Rule.OneShiftPerEmployeePerDay();
    }

    /** Creates a {@link Rule} capping an employee's assignments in any rolling 7-day window. */
    public static Rule maxShiftsPerWeek(int maxShifts) {
        return new Rule.MaxShiftsPerWeek(maxShifts);
    }

    /** Creates a {@link Rule} capping an employee's consecutive night shifts (those spanning midnight). */
    public static Rule maxConsecutiveNightShifts(int maxNights) {
        return new Rule.MaxConsecutiveNightShifts(maxNights);
    }

    /**
     * Creates a {@link Rule} forbidding a {@code toCategory} shift on the day right after a
     * {@code fromCategory} shift for the same employee (for example no "MORNING" after "NIGHT").
     * <p>
     * Unlike {@link #minRestBetweenShifts(Duration)}, which measures the clock gap between two
     * shifts, this rule is categorical and day-based: it applies whatever the shift times are.
     */
    public static Rule forbidSequence(String fromCategory, String toCategory) {
        Validate.nonBlank(fromCategory, "fromCategory");
        Validate.nonBlank(toCategory, "toCategory");
        return new Rule.ForbidSequence(fromCategory, toCategory);
    }

    private Rules() {}
}

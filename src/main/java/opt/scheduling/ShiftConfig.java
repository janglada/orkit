package opt.scheduling;

import opt.ModelException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/** Fluent configurator for a shift, used inside the lambda passed to
 *  {@link SchedulingBuilder#shift(String, java.util.function.Consumer)}. */
public final class ShiftConfig {

    String id;
    DayOfWeek day;
    LocalDate specificDate;
    LocalTime from;
    LocalTime to;
    int needsCount = 1;
    String requiredSkill;

    ShiftConfig() {}

    /**
     * Places the shift on the first date in the horizon whose day-of-week matches.
     * For recurring weekly shifts across a multi-week horizon, define one shift per
     * occurrence or use {@link #on(LocalDate)} to target a specific calendar date.
     */
    public ShiftConfig on(DayOfWeek day) {
        this.day = day;
        return this;
    }

    /** Places the shift on a specific calendar date, which must fall within the horizon. */
    public ShiftConfig on(LocalDate date) {
        this.specificDate = date;
        return this;
    }

    /** Sets the shift's daily start time. */
    public ShiftConfig from(LocalTime start) {
        this.from = start;
        return this;
    }

    /** Sets the shift's daily end time; may be earlier than {@code from} to model an overnight shift. */
    public ShiftConfig to(LocalTime end) {
        this.to = end;
        return this;
    }

    /** Sets how many qualified employees this shift requires. */
    public ShiftConfig needs(int count) {
        if (count < 1) throw new ModelException("needs() must be >= 1, got: " + count);
        this.needsCount = count;
        return this;
    }

    /** Requires that any employee assigned to this shift possess the given skill. */
    public ShiftConfig withSkill(String skill) {
        this.requiredSkill = skill;
        return this;
    }
}

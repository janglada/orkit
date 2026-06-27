package opt.scheduling;

import opt.ModelException;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** Fluent configurator for a shift, used inside the lambda passed to
 *  {@link SchedulingBuilder#shift(String, java.util.function.Consumer)}. */
public final class ShiftConfig {

    String id;
    DayOfWeek day;
    LocalTime from;
    LocalTime to;
    int needsCount = 1;
    String requiredSkill;

    ShiftConfig() {}

    public ShiftConfig on(DayOfWeek day) {
        this.day = day;
        return this;
    }

    public ShiftConfig from(LocalTime start) {
        this.from = start;
        return this;
    }

    public ShiftConfig to(LocalTime end) {
        this.to = end;
        return this;
    }

    public ShiftConfig needs(int count) {
        if (count < 1) throw new ModelException("needs() must be >= 1, got: " + count);
        this.needsCount = count;
        return this;
    }

    public ShiftConfig withSkill(String skill) {
        this.requiredSkill = skill;
        return this;
    }
}

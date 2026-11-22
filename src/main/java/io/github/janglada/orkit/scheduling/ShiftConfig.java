package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fluent configurator for a shift, used inside the lambda passed to
 * {@link SchedulingBuilder#shift(String, java.util.function.Consumer)}.
 * <p>
 * A shift is placed either on one concrete date ({@link #on(LocalDate)}) or on a weekly
 * recurrence ({@link #every(DayOfWeek...)}, {@link #daily()}). A recurring shift is expanded
 * into one instance per matching date in the horizon; every instance shares the declared id
 * and is told apart by its {@link ShiftAssignment#date() date}.
 */
public final class ShiftConfig {

    String id;
    final Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
    boolean daily = false;
    LocalDate specificDate;
    LocalTime from;
    LocalTime to;
    int needsCount = 1;
    int minCount = -1;   // -1 = same as needsCount (hard staffing)
    final Set<String> requiredSkills = new LinkedHashSet<>();
    final Map<String, Integer> roles = new LinkedHashMap<>();
    String category;

    ShiftConfig() {}

    /** Repeats the shift on every listed weekday within the horizon. */
    public ShiftConfig every(DayOfWeek... days) {
        if (days == null || days.length == 0) throw new ModelException("every() requires at least one day.");
        this.days.addAll(Arrays.asList(days));
        return this;
    }

    /** Repeats the shift on every day of the horizon. */
    public ShiftConfig daily() {
        this.daily = true;
        return this;
    }

    /** Places the shift on every occurrence of {@code day} in the horizon; alias of {@link #every(DayOfWeek...)}. */
    public ShiftConfig on(DayOfWeek day) {
        return every(day);
    }

    /** Places the shift on a specific calendar date, which must fall within the horizon. */
    public ShiftConfig on(LocalDate date) {
        if (date == null) throw new ModelException("on(LocalDate) must not be null.");
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

    /**
     * Sets how many qualified employees this shift requires. This is a hard requirement unless
     * {@link #needsAtLeast(int)} lowers the floor, in which case it becomes the soft target.
     */
    public ShiftConfig needs(int count) {
        if (count < 1) throw new ModelException("needs() must be >= 1, got: " + count);
        this.needsCount = count;
        return this;
    }

    /**
     * Sets a hard minimum head-count below {@link #needs(int)}. Each missing employee between
     * the minimum and the target is penalized with {@link Goals#penalizeUnderstaffing(long)}
     * (or its default weight when that goal is not declared).
     */
    public ShiftConfig needsAtLeast(int min) {
        Validate.nonNegative(min, "needsAtLeast()");
        this.minCount = min;
        return this;
    }

    /** Requires that any employee assigned to this shift possess the given skill. */
    public ShiftConfig withSkill(String skill) {
        return withSkills(skill);
    }

    /** Requires that any employee assigned to this shift possess every given skill. */
    public ShiftConfig withSkills(String... skills) {
        for (String skill : skills) {
            Validate.nonBlank(skill, "skill");
            requiredSkills.add(skill);
        }
        return this;
    }

    /**
     * Requires {@code count} of the assigned employees to fill a role needing {@code skill}, for
     * a skill mix such as one charge nurse among four nurses. Each employee fills at most one
     * role; the remaining assignees fill none. The role counts must not exceed the shift's hard
     * minimum head-count. The filled role is reported in {@link ShiftAssignment#role()}.
     */
    public ShiftConfig role(String skill, int count) {
        Validate.nonBlank(skill, "role skill");
        Validate.atLeastOne(count, "role count");
        roles.merge(skill, count, Integer::sum);
        return this;
    }

    /** Tags the shift with a category (for example "NIGHT", "MORNING") used by {@link Rules#forbidSequence(String, String)}. */
    public ShiftConfig category(String category) {
        Validate.nonBlank(category, "category");
        this.category = category;
        return this;
    }
}

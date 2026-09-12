package opt.scheduling;

import opt.ModelException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fluent configurator for an employee, used inside the lambda passed to
 *  {@link SchedulingBuilder#employee(String, java.util.function.Consumer)}. */
public final class EmployeeConfig {

    String id;
    final Set<String> skills = new LinkedHashSet<>();
    int costPerHour = 0;
    final Set<DayOfWeek> availableDays = EnumSet.noneOf(DayOfWeek.class);
    int maxHoursPerWeek = 0;
    final Set<String> preferredShifts = new LinkedHashSet<>();
    final Set<LocalDate> blockedDates = new LinkedHashSet<>();

    EmployeeConfig() {}

    /** Adds one or more skills possessed by this employee. */
    public EmployeeConfig skills(String... skills) {
        this.skills.addAll(Arrays.asList(skills));
        return this;
    }

    /** Sets this employee's hourly labor cost, used by the {@link Goals#totalLaborCost()} objective. */
    public EmployeeConfig costPerHour(int cost) {
        if (cost < 0) throw new ModelException("costPerHour must be >= 0, got: " + cost);
        this.costPerHour = cost;
        return this;
    }

    /** Restricts this employee's working days; if left empty the employee is available every day of the week. */
    public EmployeeConfig availableOn(DayOfWeek... days) {
        this.availableDays.addAll(Arrays.asList(days));
        return this;
    }

    /** Caps this employee's scheduled hours in any 7 consecutive days of the horizon (rolling week). */
    public EmployeeConfig maxHoursPerWeek(int hours) {
        if (hours <= 0) throw new ModelException("maxHoursPerWeek must be > 0, got: " + hours);
        this.maxHoursPerWeek = hours;
        return this;
    }

    /** Records that this employee prefers the given shift (used by {@code honorEmployeePreferences()}). */
    public EmployeeConfig prefersShift(String shiftId) {
        this.preferredShifts.add(shiftId);
        return this;
    }

    /** Blocks one or more specific calendar dates regardless of day-of-week availability. */
    public EmployeeConfig unavailableOn(LocalDate... dates) {
        blockedDates.addAll(Arrays.asList(dates));
        return this;
    }

    /**
     * Blocks every date in the inclusive range {@code [from, to]}.
     * Use this for holidays, leave, or any multi-day absence.
     */
    public EmployeeConfig onHoliday(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ModelException("Holiday end date must not be before start date");
        }
        from.datesUntil(to.plusDays(1)).forEach(blockedDates::add);
        return this;
    }
}

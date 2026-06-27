package opt.scheduling;

import opt.ModelException;

import java.time.DayOfWeek;
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

    EmployeeConfig() {}

    public EmployeeConfig skills(String... skills) {
        this.skills.addAll(Arrays.asList(skills));
        return this;
    }

    public EmployeeConfig costPerHour(int cost) {
        if (cost < 0) throw new ModelException("costPerHour must be >= 0, got: " + cost);
        this.costPerHour = cost;
        return this;
    }

    public EmployeeConfig availableOn(DayOfWeek... days) {
        this.availableDays.addAll(Arrays.asList(days));
        return this;
    }

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
}

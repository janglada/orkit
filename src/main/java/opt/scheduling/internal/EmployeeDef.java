package opt.scheduling.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Immutable description of an employee built by {@link opt.scheduling.EmployeeConfig}.
 *  @param id unique employee identifier
 *  @param skills set of skills the employee possesses
 *  @param costPerHour hourly labor cost used by cost-minimization objectives
 *  @param availableDays day-of-week pattern; empty means available every day
 *  @param maxHoursPerWeek weekly hour cap, or 0 for no cap
 *  @param preferredShifts shift ids this employee would prefer to work
 *  @param blockedDates specific calendar dates the employee cannot work */
public record EmployeeDef(
        String id,
        Set<String> skills,
        int costPerHour,
        Set<DayOfWeek> availableDays,
        int maxHoursPerWeek,
        Set<String> preferredShifts,
        Set<LocalDate> blockedDates
) {
    /** Returns false if the day-of-week is outside the employee's working days. */
    public boolean isAvailableOn(DayOfWeek day) {
        return availableDays.isEmpty() || availableDays.contains(day);
    }

    /**
     * Full availability check: respects both the day-of-week pattern and any
     * specific blocked dates (single days, holidays, leave periods).
     */
    public boolean isAvailableOn(DayOfWeek day, LocalDate date) {
        if (date != null && blockedDates.contains(date)) return false;
        return isAvailableOn(day);
    }

    /** Returns true if the employee possesses the given skill, or if the shift requires no skill. */
    public boolean hasSkill(String skill) {
        return skill == null || skills.contains(skill);
    }
}

package opt.scheduling.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

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

    public boolean hasSkill(String skill) {
        return skill == null || skills.contains(skill);
    }
}

package opt.scheduling.internal;

import java.time.DayOfWeek;
import java.util.Set;

public record EmployeeDef(
        String id,
        Set<String> skills,
        int costPerHour,
        Set<DayOfWeek> availableDays,
        int maxHoursPerWeek,
        Set<String> preferredShifts
) {
    public boolean isAvailableOn(DayOfWeek day) {
        return availableDays.isEmpty() || availableDays.contains(day);
    }

    public boolean hasSkill(String skill) {
        return skill == null || skills.contains(skill);
    }
}

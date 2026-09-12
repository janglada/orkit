package opt.scheduling.internal;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Immutable description of one shift instance on a concrete date, produced by expanding a
 * {@link opt.scheduling.ShiftConfig} over the horizon.
 *
 * @param id            unique instance id (internal; {@code templateId} for a single-date shift,
 *                      {@code templateId@date} for a recurring one)
 * @param templateId    the shift id the user declared; reported in {@link opt.scheduling.ShiftAssignment#shift()}
 * @param date          the calendar date of this instance
 * @param from          shift start time
 * @param to            shift end time; may be before {@code from} to model an overnight shift
 * @param needsCount    target number of qualified employees
 * @param minCount      hard minimum number of employees ({@code == needsCount} for hard staffing)
 * @param requiredSkill skill employees must have, or null for an unskilled shift
 * @param category      free-form category used by sequence rules, or null
 */
public record ShiftDef(
        String id,
        String templateId,
        LocalDate date,
        LocalTime from,
        LocalTime to,
        int needsCount,
        int minCount,
        String requiredSkill,
        String category
) {
    /** Duration in whole minutes. Handles overnight shifts where {@code to} is before {@code from}. */
    public int durationMinutes() {
        int start = from.toSecondOfDay() / 60;
        int end   = to.toSecondOfDay() / 60;
        return end > start ? end - start : (24 * 60 - start) + end;
    }

    /** A night shift is one that spans midnight, i.e. {@code to} is strictly before {@code from}. */
    public boolean isNightShift() {
        return to.isBefore(from);
    }

    /** Whether staffing below {@code needsCount} is allowed (down to {@code minCount}). */
    public boolean isSoftStaffed() {
        return minCount < needsCount;
    }

    /**
     * Start time in absolute minutes from the beginning of the horizon.
     * @param dayIndex 0-based position of this shift's day within the horizon
     */
    public int absoluteStartMinutes(int dayIndex) {
        return dayIndex * 24 * 60 + from.toSecondOfDay() / 60;
    }

    /**
     * End time in absolute minutes from the beginning of the horizon.
     * @param dayIndex 0-based position of this shift's day within the horizon
     */
    public int absoluteEndMinutes(int dayIndex) {
        return absoluteStartMinutes(dayIndex) + durationMinutes();
    }
}

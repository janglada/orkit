package opt.scheduling.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftDef(
        String id,
        DayOfWeek day,
        LocalDate specificDate,
        LocalTime from,
        LocalTime to,
        int needsCount,
        String requiredSkill
) {
    /** Duration in whole minutes. Handles overnight shifts (to < from). */
    public int durationMinutes() {
        int start = from.toSecondOfDay() / 60;
        int end   = to.toSecondOfDay() / 60;
        return end > start ? end - start : (24 * 60 - start) + end;
    }

    /** A night shift is one that spans midnight, i.e. {@code to} is strictly before {@code from}. */
    public boolean isNightShift() {
        return to.isBefore(from);
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

    /** Returns the concrete date for this shift within the given horizon, or null if day not in horizon.
     *  Note: this returns the first occurrence of {@link #day()} and does not consider {@link #specificDate()}. */
    public LocalDate dateIn(LocalDate horizonStart, int horizonDays) {
        for (int i = 0; i < horizonDays; i++) {
            LocalDate d = horizonStart.plusDays(i);
            if (d.getDayOfWeek() == day) {
                return d;
            }
        }
        return null;
    }
}

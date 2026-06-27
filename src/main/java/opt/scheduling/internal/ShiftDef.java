package opt.scheduling.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftDef(
        String id,
        DayOfWeek day,
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

    /** Whether this shift's rest gap with another shift is shorter than minRestMinutes. */
    public boolean tooCloseWith(ShiftDef other, int minRestMinutes) {
        // Gap = end of this to start of other (or end of other to start of this)
        int myEnd    = from.toSecondOfDay() / 60 + durationMinutes();
        int otherEnd = other.from().toSecondOfDay() / 60 + other.durationMinutes();
        int gapAB    = other.from().toSecondOfDay() / 60 - myEnd;
        int gapBA    = from.toSecondOfDay() / 60 - otherEnd;
        // Consider only forward gaps (other starts after this ends, or this starts after other ends)
        return (gapAB >= 0 && gapAB < minRestMinutes) || (gapBA >= 0 && gapBA < minRestMinutes);
    }

    /** Returns the concrete date for this shift within the given horizon, or null if day not in horizon. */
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

package opt.booking.internal;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/**
 * A same-day availability window that repeats on the given weekdays (every day when
 * {@code daily}); expanded into concrete slots over the horizon when the problem is built.
 *
 * @param days  weekdays the window applies to; ignored when {@code daily}
 * @param daily whether the window applies every day
 * @param from  window start, inclusive
 * @param to    window end, exclusive; strictly after {@code from}
 */
public record WeeklyWindow(Set<DayOfWeek> days, boolean daily, LocalTime from, LocalTime to) {
    public WeeklyWindow {
        days = Set.copyOf(days);
    }
}

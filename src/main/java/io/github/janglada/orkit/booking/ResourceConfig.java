package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.booking.internal.WeeklyWindow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Fluent configurator for a single bookable resource.
 * Used inside the lambda passed to
 * {@link BookingBuilder#resource(String, java.util.function.Consumer)}.
 *
 * <p>A resource with no declared availability is bookable throughout the horizon. Availability
 * may be declared as explicit {@link TimeSlot}s, as weekly windows ({@link #available(DayOfWeek,
 * LocalTime, LocalTime)}), or daily windows ({@link #availableDaily(LocalTime, LocalTime)}), in
 * any combination; overlapping or touching windows are merged when the problem is built.
 */
public final class ResourceConfig {

    final List<TimeSlot> slots = new ArrayList<>();
    final List<WeeklyWindow> weekly = new ArrayList<>();

    ResourceConfig() {}

    /** Declares that this resource is available during {@code slot}. Repeatable. */
    public ResourceConfig available(TimeSlot slot) {
        if (slot == null) throw new ModelException("available() slot must not be null.");
        slots.add(slot);
        return this;
    }

    /** Declares availability between {@code from} and {@code to} on every listed weekday within the horizon. */
    public ResourceConfig available(DayOfWeek day, LocalTime from, LocalTime to) {
        return available(new DayOfWeek[]{day}, from, to);
    }

    /** Declares availability between {@code from} and {@code to} on every listed weekday within the horizon. */
    public ResourceConfig available(DayOfWeek[] days, LocalTime from, LocalTime to) {
        if (days == null || days.length == 0) throw new ModelException("available() requires at least one day.");
        validateWindow(from, to);
        weekly.add(new WeeklyWindow(EnumSet.copyOf(Arrays.asList(days)), false, from, to));
        return this;
    }

    /** Declares availability between {@code from} and {@code to} on every day of the horizon. */
    public ResourceConfig availableDaily(LocalTime from, LocalTime to) {
        validateWindow(from, to);
        weekly.add(new WeeklyWindow(EnumSet.noneOf(DayOfWeek.class), true, from, to));
        return this;
    }

    private static void validateWindow(LocalTime from, LocalTime to) {
        if (from == null || to == null) throw new ModelException("available(): from and to must not be null.");
        if (!to.isAfter(from)) {
            throw new ModelException("available(): from must be strictly before to (overnight windows are not supported), got "
                    + from + " to " + to + ".");
        }
    }
}

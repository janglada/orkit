package opt.booking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fluent configurator for a single bookable resource.
 * Used inside the lambda passed to
 * {@link BookingBuilder#resource(String, java.util.function.Consumer)}.
 *
 * <p>A resource with no declared availability is treated as available 24/7 over the
 * solver horizon. A resource with exactly one {@link TimeSlot} is only bookable inside
 * that window; multi-slot availability is not yet supported.
 */
public final class ResourceConfig {

    final List<TimeSlot> slots = new ArrayList<>();

    ResourceConfig() {}

    /** Declares that this resource is available during {@code slot}. May be called once per resource. */
    public ResourceConfig available(TimeSlot slot) {
        slots.add(slot);
        return this;
    }

    /** The availability slots declared so far, in insertion order. */
    List<TimeSlot> slots() {
        return Collections.unmodifiableList(slots);
    }
}

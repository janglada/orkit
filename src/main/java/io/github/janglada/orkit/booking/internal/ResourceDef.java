package io.github.janglada.orkit.booking.internal;

import java.util.List;

/**
 * Internal definition of a bookable resource: its id and normalized availability windows
 * (sorted, non-overlapping, as minute offsets from the epoch). An empty list means the
 * resource is available throughout the horizon.
 */
public record ResourceDef(String id, List<SlotWindow> slots) {
    /** Defensive copy of the slots list. */
    public ResourceDef {
        slots = List.copyOf(slots);
    }
}

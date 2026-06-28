package opt.booking.internal;

import opt.booking.TimeSlot;

import java.util.List;

/** Internal definition of a bookable resource: its id and availability slots. */
public record ResourceDef(String id, List<TimeSlot> slots) {
    /** Defensive copy of the slots list. */
    public ResourceDef {
        slots = List.copyOf(slots);
    }
}

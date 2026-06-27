package opt.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;

/** A single employee-to-shift assignment in a solved roster. */
public record ShiftAssignment(
        String employee,
        String shift,
        LocalDate date,
        LocalTime start,
        LocalTime end
) {}

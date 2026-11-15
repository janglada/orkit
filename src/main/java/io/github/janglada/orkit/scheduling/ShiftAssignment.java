package io.github.janglada.orkit.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;

/** A single employee-to-shift assignment in a solved roster.
 *  @param employee id of the assigned employee
 *  @param shift id of the shift being staffed
 *  @param date concrete calendar date the shift falls on
 *  @param start start time of the shift on that date
 *  @param end end time of the shift (may be on the following day for overnight shifts) */
public record ShiftAssignment(
        String employee,
        String shift,
        LocalDate date,
        LocalTime start,
        LocalTime end
) {}

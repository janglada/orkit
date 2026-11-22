package io.github.janglada.orkit.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;

/** A single employee-to-shift assignment in a solved roster.
 *  @param employee id of the assigned employee
 *  @param shift id of the shift being staffed
 *  @param date concrete calendar date the shift falls on
 *  @param start start time of the shift on that date
 *  @param end end time of the shift (may be on the following day for overnight shifts)
 *  @param role the {@link ShiftConfig#role(String, int) role} skill the employee fills, or null
 *  @param cost the employee's {@link EmployeeConfig#costPerHour(int) hourly cost} times the shift's hours */
public record ShiftAssignment(
        String employee,
        String shift,
        LocalDate date,
        LocalTime start,
        LocalTime end,
        String role,
        double cost
) {
    /** Length of the shift in minutes; overnight shifts wrap past midnight. */
    public long minutes() {
        long m = java.time.Duration.between(start, end).toMinutes();
        return m > 0 ? m : m + 24 * 60;
    }
}

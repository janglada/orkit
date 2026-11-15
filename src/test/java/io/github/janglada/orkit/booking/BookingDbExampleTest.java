package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven, real-world example test for the appointment-booking solver.
 *
 * <p>Models a <b>medical clinic scheduling</b> scenario: two doctors (Dr. Lee,
 * Dr. Patel) and a shared procedure room serve 8 appointments across a 2-day
 * horizon (Mon–Tue 2026-07-13/14). Each resource has a daily availability slot;
 * the procedure room is open 24/7. Appointments declare a duration, candidate
 * resources, optional time windows, and an unscheduled penalty. The data lives
 * in a SQLite database loaded from {@code src/test/resources/db/booking.sql}.
 *
 * <p>The test loads the database, builds the model through the fluent DSL, solves
 * it, asserts the structural invariants (no overlap per resource, appointments
 * within their time windows), and prints a readable schedule — so it doubles as
 * a runnable example.
 *
 * <p>Run with:
 * <pre>{@code
 *   mvn test -Dtest=BookingDbExampleTest
 * }</pre>
 */
class BookingDbExampleTest {

    @Test
    void clinicSchedulingFromDatabaseIsFeasibleAndNoOverlap() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/booking.sql")) {

            // ── Load horizon ──────────────────────────────────────────────────
            Map<String, Object> hRow = db.select("SELECT * FROM horizon").get(0);
            LocalDate horizonStart = LocalDate.parse(TestDatabase.asString(hRow, "start_date"));
            int horizonDays = TestDatabase.asInt(hRow, "days");

            // ── Load resources ────────────────────────────────────────────────
            List<Map<String, Object>> resourceRows =
                    db.select("SELECT id, slot_start, slot_end FROM resource ORDER BY id");

            // ── Load appointments ──────────────────────────────────────────────
            List<Map<String, Object>> appointmentRows =
                    db.select("SELECT id, duration_min, resource_ids, earliest, latest, penalty FROM appointment ORDER BY id");

            var builder = Optimize.booking()
                    .horizon(horizonStart, horizonDays);

            // Parse resource availability slots.
            Map<String, TimeSlot> resourceSlot = new HashMap<>();
            for (Map<String, Object> row : resourceRows) {
                String id = TestDatabase.asString(row, "id");
                String slotStart = (String) row.get("slot_start");
                String slotEnd = (String) row.get("slot_end");

                if (slotStart != null && slotEnd != null) {
                    // Day-specific slot: create one per day of the horizon.
                    TimeSlot ts = new TimeSlot(
                            LocalDateTime.of(horizonStart, LocalTime.parse(slotStart)),
                            LocalDateTime.of(horizonStart, LocalTime.parse(slotEnd)));
                    resourceSlot.put(id, ts);
                    builder.resource(id, r -> r.available(ts));
                } else {
                    // 24/7 availability (no slot constraint).
                    resourceSlot.put(id, null);
                    builder.resource(id, r -> {});
                }
            }

            // Parse appointments.
            for (Map<String, Object> row : appointmentRows) {
                String id = TestDatabase.asString(row, "id");
                long durationMin = TestDatabase.asLong(row, "duration_min");
                String resourceIds = TestDatabase.asString(row, "resource_ids");
                String earliestStr = (String) row.get("earliest");
                String latestStr = (String) row.get("latest");
                long penalty = TestDatabase.asLong(row, "penalty");

                String[] resources = resourceIds.split(",");
                builder.appointment(id, a -> {
                    a.duration(Duration.ofMinutes(durationMin))
                            .requiresAnyOf(resources)
                            .penaltyIfUnscheduled(penalty);
                    if (earliestStr != null) {
                        a.earliest(LocalDateTime.parse(earliestStr.replace(" ", "T")));
                    }
                    if (latestStr != null) {
                        a.latest(LocalDateTime.parse(latestStr.replace(" ", "T")));
                    }
                });
            }

            // ── Solve ─────────────────────────────────────────────────────────
            builder.goal(Goals.minimizeUnscheduledPenalty());
            BookingSolution sol = builder.solver()
                    .timeLimit(Duration.ofSeconds(10))
                    .workers(8)
                    .solve();

            // ── Assertions ────────────────────────────────────────────────────
            assertThat(sol.isFeasible())
                    .as("clinic schedule must be feasible")
                    .isTrue();

            // At least half the appointments should be scheduled.
            assertThat(sol.assignments().size())
                    .as("at least 4 of 8 appointments should be scheduled")
                    .isGreaterThanOrEqualTo(4);

            // No overlap per resource.
            Map<String, List<AppointmentAssignment>> byResource = new LinkedHashMap<>();
            for (AppointmentAssignment a : sol.assignments()) {
                byResource.computeIfAbsent(a.resourceId(), k -> new ArrayList<>()).add(a);
            }
            for (List<AppointmentAssignment> onResource : byResource.values()) {
                List<AppointmentAssignment> sorted = onResource.stream()
                        .sorted(Comparator.comparing(AppointmentAssignment::start))
                        .toList();
                for (int i = 0; i < sorted.size() - 1; i++) {
                    AppointmentAssignment a = sorted.get(i);
                    AppointmentAssignment b = sorted.get(i + 1);
                    assertThat(a.end())
                            .as("Overlap on resource between %s and %s", a.appointmentId(), b.appointmentId())
                            .isBeforeOrEqualTo(b.start());
                }
            }

            // Every scheduled appointment is within its time window (if any).
            // The URGENT-01 must be on day 1 within 08:00–12:00.
            boolean urgentScheduled = false;
            for (AppointmentAssignment a : sol.assignments()) {
                if (a.appointmentId().equals("URGENT-01")) {
                    urgentScheduled = true;
                    assertThat(a.start())
                            .as("URGENT-01 must start at or after 08:00 on day 1")
                            .isAfterOrEqualTo(horizonStart.atTime(8, 0));
                    assertThat(a.end())
                            .as("URGENT-01 must end at or before 12:00 on day 1")
                            .isBeforeOrEqualTo(horizonStart.atTime(12, 0));
                }
            }
            assertThat(urgentScheduled)
                    .as("URGENT-01 with penalty 100 must be scheduled")
                    .isTrue();

            // ── Readable report (doubles as a runnable example) ────────────────
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  Clinic Scheduling — DB-driven Booking       ║");
            System.out.println("╚══════════════════════════════════════════════╝");
            System.out.printf("%nStatus       : %s%n", sol.status());
            sol.objective().ifPresent(v -> System.out.printf("Objective    : %.0f%n", v));
            System.out.printf("Horizon      : %s for %d day(s)%n", horizonStart, horizonDays);
            System.out.printf("Scheduled    : %d / %d%n",
                    sol.assignments().size(), appointmentRows.size());

            System.out.println("\n── Schedule ────────────────────────────────────────────────");
            System.out.printf("%-14s  %-10s  %-8s  %-8s%n", "Appointment", "Resource", "Start", "End");
            System.out.println("-".repeat(50));
            for (AppointmentAssignment a : sol.assignments()) {
                System.out.printf("%-14s  %-10s  %s   %s%n",
                        a.appointmentId(), a.resourceId(),
                        a.start().toLocalTime(), a.end().toLocalTime());
            }
            if (!sol.unscheduled().isEmpty()) {
                System.out.printf("%nUnscheduled: %s%n", String.join(", ", sol.unscheduled()));
            }
        }
    }
}
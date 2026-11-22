package io.github.janglada.orkit.examples;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.booking.BookingSolution;
import io.github.janglada.orkit.booking.AppointmentAssignment;
import io.github.janglada.orkit.booking.Goals;
import io.github.janglada.orkit.booking.TimeSlot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Clinic appointment booking — assign patient appointments to exam rooms so that
 * the total unscheduled-penalty is minimized, while no two appointments on the same
 * room ever overlap and every appointment stays within its room's availability window.
 *
 * <p>Demonstrates: optional CP-SAT interval variables, per-resource {@code NoOverlap},
 * single-slot availability windows, and penalty-weighted objective selection when
 * capacity is tight.
 *
 * <p>Three exam rooms, each available 08:00–16:00 (an 8-hour slot); eight appointments
 * of varying durations and priorities. When total demand exceeds capacity the solver
 * drops the lowest-priority appointments first.
 *
 * Run: mvn exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.ClinicAppointmentBooking
 */
public class ClinicAppointmentBooking {

    /**
     * Builds the clinic-booking model, solves it with CP-SAT, and prints the per-room
     * schedule plus the list of appointments that could not be accommodated.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║  Clinic Appointment Booking                 ║");
        System.out.println("╚═════════════════════════════════════════════╝");

        LocalDate day = LocalDate.of(2025, 3, 10);
        LocalDateTime open  = day.atTime(8, 0);
        LocalDateTime close = day.atTime(16, 0);
        TimeSlot roomHours = new TimeSlot(open, close);

        BookingSolution solution = Optimize.booking()
                .horizon(day, 1)
                .goal(Goals.minimizeUnscheduledPenalty())
                .resource("Room-A", r -> r.available(roomHours))
                .resource("Room-B", r -> r.available(roomHours))
                .resource("Room-C", r -> r.available(roomHours))
                .resource("Surgeon", r -> r.available(new TimeSlot(day.atTime(10, 0), close)))

                .appointment("P1-Checkup",      a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(5))
                .appointment("P2-Vaccination",  a -> a.duration(Duration.ofMinutes(15)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(2))
                .appointment("P3-Consultation", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(10))
                .appointment("P4-MinorSurgery", a -> a.duration(Duration.ofMinutes(120)).requiresAnyOf("Room-A", "Room-B").alsoRequiresAnyOf("Surgeon").penaltyIfUnscheduled(20))
                .appointment("P5-FollowUp",     a -> a.duration(Duration.ofMinutes(20)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(3))
                .appointment("P6-Checkup",      a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(5))
                .appointment("P7-Consultation",a -> a.duration(Duration.ofMinutes(90)).requiresAnyOf("Room-A", "Room-C").penaltyIfUnscheduled(15))
                .appointment("P8-Vaccination",  a -> a.duration(Duration.ofMinutes(15)).requiresAnyOf("Room-A", "Room-B", "Room-C").penaltyIfUnscheduled(2))

                .solver()
                .timeLimit(Duration.ofSeconds(10))
                .workers(8)
                .solve();

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus   : %s%n", solution.status());
        solution.objective().ifPresent(v -> System.out.printf("Objective: %.0f%n", v));
        System.out.printf("Runtime  : %d ms%n%n", solution.runtime().toMillis());

        System.out.println("── Room schedules ────────────────────────────────────────────");
        for (String room : List.of("Room-A", "Room-B", "Room-C")) {
            System.out.printf("%n%s:%n", room);
            List<AppointmentAssignment> on = solution.assignmentsOn(room);
            if (on.isEmpty()) {
                System.out.println("  (no appointments)");
                continue;
            }
            on.stream()
                    .sorted(java.util.Comparator.comparing(AppointmentAssignment::start))
                    .forEach(a -> System.out.printf("  %s  %s–%s  (%d min)%s%n",
                            pad(a.appointmentId(), 18), a.start().toLocalTime(), a.end().toLocalTime(),
                            a.duration().toMinutes(),
                            a.resourceIds().size() > 1 ? "  with " + String.join(", ", a.resourceIds().subList(1, a.resourceIds().size())) : ""));
        }

        if (!solution.unscheduled().isEmpty()) {
            System.out.printf("%n── Unscheduled ───────────────────────────────────────────────%n");
            solution.unscheduled().forEach(id -> System.out.println("  " + id));
        }
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}

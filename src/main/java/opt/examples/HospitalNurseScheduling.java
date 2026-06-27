package opt.examples;

import opt.Optimize;
import opt.SolveStatus;
import opt.scheduling.EmployeeRoster;
import opt.scheduling.ShiftAssignment;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;

/**
 * Hospital weekly nurse scheduling — 12 nurses, 21 shifts (day/evening/night × 7 days).
 *
 * Demonstrates: multi-day horizon, skill tiers, multiple shift types per day,
 * rest and consecutive-day rules, balanced workload, labor cost minimization.
 *
 * Run: mvn exec:java -Dexec.mainClass=opt.examples.HospitalNurseScheduling
 */
public class HospitalNurseScheduling {

    // Shift codes — 3 per day × 7 days = 21 shifts
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final DayOfWeek[] DOW = {
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    };
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 6); // Monday

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════╗");
        System.out.println("║      Hospital ICU Ward — Weekly Roster        ║");
        System.out.println("╚═══════════════════════════════════════════════╝");

        var builder = Optimize.scheduling().horizon(WEEK_START, 7);

        // ── Nurses ──────────────────────────────────────────────────────────
        // 4 Senior nurses (ICU + RN)
        builder.employee("Dr. Alice",  e -> e.skills("RN","ICU").costPerHour(58).maxHoursPerWeek(40).prefersShift("Mon-Day"))
               .employee("Dr. Bob",    e -> e.skills("RN","ICU").costPerHour(55).maxHoursPerWeek(40).prefersShift("Tue-Day"))
               .employee("Dr. Carol",  e -> e.skills("RN","ICU").costPerHour(56).maxHoursPerWeek(40))
               .employee("Dr. Dave",   e -> e.skills("RN","ICU").costPerHour(54).maxHoursPerWeek(36));

        // 5 Staff nurses (RN only)
        builder.employee("Nurse Eve",   e -> e.skills("RN").costPerHour(42).maxHoursPerWeek(40))
               .employee("Nurse Frank", e -> e.skills("RN").costPerHour(40).maxHoursPerWeek(40).prefersShift("Mon-Day"))
               .employee("Nurse Grace", e -> e.skills("RN").costPerHour(38).maxHoursPerWeek(36))
               .employee("Nurse Hank",  e -> e.skills("RN").costPerHour(39).maxHoursPerWeek(40))
               .employee("Nurse Iris",  e -> e.skills("RN").costPerHour(41).maxHoursPerWeek(40));

        // 3 Part-time nurses (RN, limited to weekdays)
        builder.employee("PT Jane",     e -> e.skills("RN").costPerHour(44)
                       .availableOn(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).maxHoursPerWeek(24))
               .employee("PT Kevin",    e -> e.skills("RN").costPerHour(43)
                       .availableOn(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).maxHoursPerWeek(24))
               .employee("PT Laura",    e -> e.skills("RN").costPerHour(45)
                       .availableOn(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY).maxHoursPerWeek(16));

        // ── Shifts: Day 07-15, Evening 15-23, Night 23-07 ───────────────────
        // Day and evening each need 2 RN; Night needs 1 ICU + 1 RN
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            String label = DAYS[i];
            DayOfWeek dow = DOW[i];

            builder.shift(label + "-Day", s -> s
                    .on(dow).from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                    .needs(2).withSkill("RN"));
            builder.shift(label + "-Evening", s -> s
                    .on(dow).from(LocalTime.of(15, 0)).to(LocalTime.of(23, 0))
                    .needs(2).withSkill("RN"));
            builder.shift(label + "-Night", s -> s
                    .on(dow).from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0))
                    .needs(1).withSkill("ICU"));
        }

        // ── Rules ─────────────────────────────────────────────────────────────
        builder.constrain(oneShiftPerEmployeePerDay())
               .constrain(minRestBetweenShifts(Duration.ofHours(11)))
               .constrain(maxConsecutiveWorkDays(5));

        // ── Goals ─────────────────────────────────────────────────────────────
        builder.prefer(balanceWorkloadAcrossEmployees())
               .prefer(honorEmployeePreferences())
               .minimize(totalLaborCost());

        // ── Solve ─────────────────────────────────────────────────────────────
        System.out.println("\nSolving (30 s time limit, 8 workers)…");
        long t0 = System.currentTimeMillis();

        EmployeeRoster roster = builder.solver()
                .timeLimit(Duration.ofSeconds(30))
                .workers(8)
                .verbose(false)
                .solve();

        long elapsed = System.currentTimeMillis() - t0;

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus : %s  (solved in %.1fs)%n", roster.status(), elapsed / 1000.0);
        if (!roster.isFeasible()) {
            System.out.println("No feasible roster found.");
            return;
        }

        roster.objective().ifPresent(c ->
            System.out.printf("Cost   : %.0f  (minute·$/h)  ≈  $%.2f%n",
                    c, c / 60.0));

        // Print roster grid: rows = nurses, cols = days, cells = shift type
        System.out.println();
        printRoster(roster);
        printStatistics(roster);
    }

    private static void printRoster(EmployeeRoster roster) {
        String[] nurses = {
            "Dr. Alice", "Dr. Bob", "Dr. Carol", "Dr. Dave",
            "Nurse Eve", "Nurse Frank", "Nurse Grace", "Nurse Hank", "Nurse Iris",
            "PT Jane", "PT Kevin", "PT Laura"
        };

        // Header
        System.out.printf("%-15s", "Nurse \\ Day");
        for (String d : DAYS) System.out.printf(" %-9s", d);
        System.out.println();
        System.out.println("-".repeat(15 + 10 * 7));

        for (String nurse : nurses) {
            System.out.printf("%-15s", nurse);
            for (String d : DAYS) {
                List<String> nurseShifts = roster.shiftsFor(nurse);
                String cell = "";
                if (nurseShifts.contains(d + "-Day"))     cell = "Day";
                else if (nurseShifts.contains(d + "-Evening")) cell = "Eve";
                else if (nurseShifts.contains(d + "-Night"))   cell = "Ngt";
                System.out.printf(" %-9s", cell.isEmpty() ? "." : cell);
            }
            int totalH = roster.shiftsFor(nurse).size() * 8;
            System.out.printf("  (%dh)%n", totalH);
        }
    }

    private static void printStatistics(EmployeeRoster roster) {
        System.out.println("\n── ICU night shift coverage ─────────────────────");
        for (String day : DAYS) {
            List<String> staff = roster.staffOf(day + "-Night");
            System.out.printf("  %-4s Night ICU: %s%n", day, String.join(", ", staff));
        }

        System.out.println("\n── Workload per nurse ────────────────────────────");
        String[] nurses = {
            "Dr. Alice", "Dr. Bob", "Dr. Carol", "Dr. Dave",
            "Nurse Eve", "Nurse Frank", "Nurse Grace", "Nurse Hank", "Nurse Iris",
            "PT Jane", "PT Kevin", "PT Laura"
        };
        for (String nurse : nurses) {
            int shifts = roster.shiftsFor(nurse).size();
            System.out.printf("  %-15s %d shift(s) = %dh%n", nurse, shifts, shifts * 8);
        }
    }
}

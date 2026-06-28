package opt.scheduling;

import opt.Optimize;
import opt.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven real-world example for the Scheduling solver.
 *
 * <p>Scenario: a small hospital ICU ward must be staffed for one week
 * (Mon 2026-07-06 .. Sun 2026-07-12). Eleven nurses carry skill tiers
 * (senior {@code RN,ICU}, staff {@code RN}, two part-time {@code RN} with
 * limited day-of-week availability). Three shift templates — DAY, EVENING,
 * NIGHT — are instantiated on concrete dates; night shifts require the ICU
 * skill, so only senior nurses may cover them. Friday evening is busier
 * (needs 3) to exercise the per-date coverage override.
 *
 * <p>The test loads the SQLite schema + seed data from
 * {@code src/test/resources/db/scheduling.sql} via {@link TestDatabase}, builds
 * the scheduling model with the fluent DSL (employees, shifts, rules, goals),
 * solves it, asserts the core invariants (feasibility, full coverage, skill
 * gating, max-hours), and prints a readable roster grid plus a coverage
 * summary — so it doubles as a runnable example.
 *
 * <p>Run: {@code mvn test -Dtest=SchedulingDbExampleTest}
 */
class SchedulingDbExampleTest {

    private static final String[] DAY_LABELS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    @Test
    void databaseDrivenHospitalRosterIsFeasibleAndCoversAllShifts() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/scheduling.sql")) {
            // ── Load employees ───────────────────────────────────────────────
            List<Map<String, Object>> empRows = db.select("SELECT * FROM employee ORDER BY id");
            Map<String, String> nameById = new LinkedHashMap<>();
            Map<String, List<String>> skillsById = new LinkedHashMap<>();
            Map<String, Integer> costById = new LinkedHashMap<>();
            Map<String, Integer> maxHoursById = new LinkedHashMap<>();
            for (Map<String, Object> r : empRows) {
                String id = TestDatabase.asString(r, "id");
                nameById.put(id, TestDatabase.asString(r, "name"));
                skillsById.put(id, splitCsv(TestDatabase.asString(r, "skills")));
                costById.put(id, TestDatabase.asInt(r, "cost_per_hour"));
                maxHoursById.put(id, TestDatabase.asInt(r, "max_hours_per_week"));
            }

            // ── Load day-of-week availability, grouped per employee ──────────
            Map<String, List<DayOfWeek>> availById = new LinkedHashMap<>();
            for (Map<String, Object> r : db.select("SELECT * FROM employee_available_day")) {
                availById.computeIfAbsent(TestDatabase.asString(r, "employee_id"), k -> new ArrayList<>())
                        .add(dow(TestDatabase.asString(r, "day_of_week")));
            }

            // ── Load shift preferences, grouped per employee ─────────────────
            Map<String, List<String>> prefsById = new LinkedHashMap<>();
            for (Map<String, Object> r : db.select("SELECT * FROM employee_preference")) {
                prefsById.computeIfAbsent(TestDatabase.asString(r, "employee_id"), k -> new ArrayList<>())
                        .add(TestDatabase.asString(r, "shift_id"));
            }

            // ── Load shift instances joined to templates ────────────────────
            List<Map<String, Object>> shiftRows = db.select("""
                    SELECT si.id, si.shift_date, si.shift_code, si.needs,
                           st.start_time, st.end_time, st.skill
                    FROM shift_instance si
                    JOIN shift_template st ON si.shift_code = st.code
                    ORDER BY si.shift_date, st.start_time
                    """);

            // Horizon: earliest shift date over the count of distinct dates.
            LocalDate horizonStart = shiftRows.stream()
                    .map(r -> LocalDate.parse(TestDatabase.asString(r, "shift_date")))
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            int horizonDays = (int) shiftRows.stream()
                    .map(r -> TestDatabase.asString(r, "shift_date"))
                    .distinct()
                    .count();

            // ── Build the model ─────────────────────────────────────────────
            var builder = Optimize.scheduling().horizon(horizonStart, horizonDays);

            for (Map<String, Object> r : empRows) {
                final String id = TestDatabase.asString(r, "id");
                final String[] skills = skillsById.get(id).toArray(new String[0]);
                final int cost = costById.get(id);
                final int maxH = maxHoursById.get(id);
                final DayOfWeek[] avail = availById.getOrDefault(id, List.of()).toArray(new DayOfWeek[0]);
                final List<String> prefs = prefsById.getOrDefault(id, List.of());

                builder.employee(id, e -> {
                    e.skills(skills).costPerHour(cost).maxHoursPerWeek(maxH);
                    if (avail.length > 0) e.availableOn(avail);
                    for (String ps : prefs) e.prefersShift(ps);
                });
            }

            for (Map<String, Object> r : shiftRows) {
                final String sid = TestDatabase.asString(r, "id");
                final LocalDate date = LocalDate.parse(TestDatabase.asString(r, "shift_date"));
                final LocalTime from = LocalTime.parse(TestDatabase.asString(r, "start_time"));
                final LocalTime to = LocalTime.parse(TestDatabase.asString(r, "end_time"));
                final int needs = TestDatabase.asInt(r, "needs");
                final String skill = TestDatabase.asString(r, "skill");
                builder.shift(sid, s -> s.on(date).from(from).to(to).needs(needs).withSkill(skill));
            }

            // ── Rules & goals ───────────────────────────────────────────────
            builder.constrain(oneShiftPerEmployeePerDay())
                    .constrain(minRestBetweenShifts(Duration.ofHours(11)))
                    .constrain(maxConsecutiveWorkDays(5))
                    .prefer(balanceWorkloadAcrossEmployees())
                    .prefer(honorEmployeePreferences())
                    .minimize(totalLaborCost());

            // ── Solve ───────────────────────────────────────────────────────
            System.out.println("╔═══════════════════════════════════════════════╗");
            System.out.println("║   Hospital ICU Ward — DB-Driven Weekly Roster ║");
            System.out.println("╚═══════════════════════════════════════════════╝");
            System.out.println("\nSolving (10 s time limit, 8 workers)…");
            long t0 = System.currentTimeMillis();

            EmployeeRoster roster = builder.solver()
                    .timeLimit(Duration.ofSeconds(10))
                    .workers(8)
                    .verbose(false)
                    .solve();

            long elapsed = System.currentTimeMillis() - t0;
            System.out.printf("%nStatus : %s  (solved in %.1fs)%n",
                    roster.status(), elapsed / 1000.0);

            // ── Assertions ──────────────────────────────────────────────────
            assertThat(roster.isFeasible())
                    .as("roster must be feasible for the seeded ward")
                    .isTrue();

            // Every shift instance is staffed to its declared needs.
            for (Map<String, Object> r : shiftRows) {
                String sid = TestDatabase.asString(r, "id");
                int needs = TestDatabase.asInt(r, "needs");
                List<String> staff = roster.staffOf(sid);
                assertThat(staff)
                        .as("shift %s must be staffed to needs=%d", sid, needs)
                        .hasSize(needs);
            }

            // Skill gating: every assigned employee holds the shift's required skill.
            for (Map<String, Object> r : shiftRows) {
                String sid = TestDatabase.asString(r, "id");
                String skill = TestDatabase.asString(r, "skill");
                for (String emp : roster.staffOf(sid)) {
                    assertThat(skillsById.get(emp))
                            .as("employee %s on shift %s lacks required skill %s", emp, sid, skill)
                            .contains(skill);
                }
            }

            // Max hours per week: shift count × 8h must not exceed the cap.
            for (Map<String, Object> r : empRows) {
                String id = TestDatabase.asString(r, "id");
                int maxH = maxHoursById.get(id);
                int workedH = roster.shiftsFor(id).size() * 8;
                assertThat(workedH)
                        .as("employee %s exceeds max_hours_per_week=%d", id, maxH)
                        .isLessThanOrEqualTo(maxH);
            }

            // ── Printable roster ────────────────────────────────────────────
            roster.objective().ifPresent(c ->
                    System.out.printf("Cost   : %.0f  (minute·$/h)  ≈  $%.2f%n", c, c / 60.0));

            System.out.println();
            printRoster(roster, empRows, nameById);
            printCoverage(roster, shiftRows);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void printRoster(EmployeeRoster roster,
                                    List<Map<String, Object>> empRows,
                                    Map<String, String> nameById) {
        System.out.printf("%-15s", "Nurse \\ Day");
        for (String d : DAY_LABELS) System.out.printf(" %-9s", d);
        System.out.println();
        System.out.println("-".repeat(15 + 10 * DAY_LABELS.length));

        for (Map<String, Object> r : empRows) {
            String id = TestDatabase.asString(r, "id");
            System.out.printf("%-15s", nameById.get(id));
            List<String> nurseShifts = roster.shiftsFor(id);
            for (String d : DAY_LABELS) {
                String cell;
                if (nurseShifts.contains(d + "-Day"))          cell = "Day";
                else if (nurseShifts.contains(d + "-Evening")) cell = "Eve";
                else if (nurseShifts.contains(d + "-Night"))   cell = "Ngt";
                else                                           cell = ".";
                System.out.printf(" %-9s", cell);
            }
            System.out.printf("  (%dh)%n", nurseShifts.size() * 8);
        }
    }

    private static void printCoverage(EmployeeRoster roster, List<Map<String, Object>> shiftRows) {
        System.out.println("\n── Shift coverage ────────────────────────────────");
        for (Map<String, Object> r : shiftRows) {
            String sid = TestDatabase.asString(r, "id");
            int needs = TestDatabase.asInt(r, "needs");
            List<String> staff = roster.staffOf(sid);
            System.out.printf("  %-13s needs=%d  staff=%s%n", sid, needs,
                    staff.isEmpty() ? "(none)" : String.join(", ", staff));
        }
    }

    private static List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static DayOfWeek dow(String code) {
        return switch (code) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown day-of-week code: " + code);
        };
    }
}

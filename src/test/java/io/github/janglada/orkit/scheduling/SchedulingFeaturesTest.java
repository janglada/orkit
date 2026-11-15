package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.janglada.orkit.scheduling.Goals.*;
import static io.github.janglada.orkit.scheduling.Rules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deeper coverage of recurring shifts, locks, sequences and soft staffing: date-scoped
 * locks, every validation branch, interaction with the other rules, and a roster
 * invariant validator run on a two-week instance with every feature switched on.
 */
class SchedulingFeaturesTest {

    private static final LocalDate MON = LocalDate.of(2026, 7, 6);

    private static SchedulingBuilder base() {
        return Optimize.scheduling().horizon(MON, 7)
                .employee("a", e -> e.costPerHour(10))
                .employee("b", e -> e.costPerHour(20));
    }

    // ── date-scoped locks ────────────────────────────────────────────────────

    @Test
    void assignAndForbidForOneDateOnly() {
        LocalDate wed = MON.plusDays(2);
        EmployeeRoster roster = base()
                .shift("d", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                .assign("b", "d", wed)          // expensive b forced only on Wednesday
                .forbid("a", "d", MON)          // cheap a barred only on Monday
                .goal(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("d", wed)).containsExactly("b");
        assertThat(roster.staffOf("d", MON)).containsExactly("b");
        for (int d = 0; d < 7; d++) {
            LocalDate date = MON.plusDays(d);
            if (!date.equals(wed) && !date.equals(MON)) assertThat(roster.staffOf("d", date)).containsExactly("a");
        }
    }

    @Test
    void lockValidationForDates() {
        assertThatThrownBy(() -> base().shift("d", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)))
                .assign("a", "d", (LocalDate) null)).isInstanceOf(ModelException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> base().shift("d", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)))
                .forbid("a", "d", (LocalDate) null)).isInstanceOf(ModelException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> base().shift("d", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)))
                .assign("a", "d", MON.plusDays(1)).solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("on 2026-07-07");
        assertThatThrownBy(() -> base().shift("d", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                .assign("a", "d").assign("b", "d").solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("has 2 assigned employees but needs 1");
    }

    @Test
    void duplicateLockOnSamePairIsIdempotent() {
        EmployeeRoster roster = base()
                .shift("d", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                .assign("b", "d").assign("b", "d", MON)
                .goal(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(roster.staffOf("d")).containsExactly("b");
    }

    // ── config validation branches ───────────────────────────────────────────

    @Test
    void shiftConfigValidation() {
        assertThatThrownBy(() -> base().shift("s", s -> s.every())).isInstanceOf(ModelException.class).hasMessageContaining("every()");
        assertThatThrownBy(() -> base().shift("s", s -> s.on((LocalDate) null))).isInstanceOf(ModelException.class).hasMessageContaining("on(LocalDate)");
        assertThatThrownBy(() -> base().shift("s", s -> s.category(" "))).isInstanceOf(ModelException.class).hasMessageContaining("category");
        assertThatThrownBy(() -> base().shift("s", s -> s.daily().from(LocalTime.of(8, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining(".to(");
        assertThatThrownBy(() -> base().shift("s", s -> s.daily().on(MON).from(LocalTime.of(8, 0)).to(LocalTime.of(9, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> base().shift("s", s -> s.from(LocalTime.of(8, 0)).to(LocalTime.of(9, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> base().shift("s", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(9, 0)).needsAtLeast(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("needsAtLeast()");
    }

    @Test
    void goalFactoriesAndWeights() {
        assertThatThrownBy(() -> penalizeUnderstaffing(0)).isInstanceOf(ModelException.class).hasMessageContaining("understaffing weight");
        assertThat(penalizeUnderstaffing()).isEqualTo(new SoftGoal.PenalizeUnderstaffing(SoftGoal.DEFAULT_UNDERSTAFFING_WEIGHT));
        assertThat(balanceWorkloadAcrossEmployees(7)).isEqualTo(new SoftGoal.BalanceWorkloadAcrossEmployees(7));
        assertThatThrownBy(() -> forbidSequence("", "X")).isInstanceOf(ModelException.class);
    }

    @Test
    void understaffingGoalAloneStillProducesAnObjective() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MON, 1)
                .employee("a", e -> {})
                .shift("s", s -> s.on(MON).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(3).needsAtLeast(1))
                .prefer(penalizeUnderstaffing(7))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.objective()).hasValue(14.0);          // 2 missing × 7
    }

    @Test
    void needsAtLeastZeroAllowsAnEmptyShift() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MON, 1)
                .employee("a", e -> e.skills("X"))
                .shift("s", s -> s.on(MON).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).needsAtLeast(0).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(roster.isFeasible()).isTrue();          // nobody qualifies, but zero is allowed
        assertThat(roster.assignments()).isEmpty();
    }

    // ── interactions ────────────────────────────────────────────────────────

    @Test
    void minRestAppliesAcrossRecurringInstances() {
        // A nightly 22:00-06:00 shift followed by a daily 08:00-16:00 shift: 2 h gap < 11 h rest,
        // so one employee cannot do night(d) and day(d+1). With two employees it is feasible.
        assertThat(Optimize.scheduling().horizon(MON, 3)
                .employee("a", e -> {})
                .shift("night", s -> s.daily().from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1))
                .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                .constrain(minRestBetweenShifts(Duration.ofHours(11)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve().isFeasible()).isFalse();
        EmployeeRoster two = Optimize.scheduling().horizon(MON, 3)
                .employee("a", e -> {}).employee("b", e -> {})
                .shift("night", s -> s.daily().from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1))
                .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                .constrain(minRestBetweenShifts(Duration.ofHours(11)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(two.isFeasible()).isTrue();
        for (ShiftAssignment n : two.assignments()) {
            if (!n.shift().equals("night")) continue;
            assertThat(two.staffOf("day", n.date().plusDays(1))).doesNotContain(n.employee());
        }
    }

    @Test
    void forbidSequenceOverRecurringShiftsAcrossTwoWeeks() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MON, 14)
                .employee("a", e -> {}).employee("b", e -> {}).employee("c", e -> {})
                .shift("N", s -> s.daily().from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).category("NIGHT"))
                .shift("M", s -> s.daily().from(LocalTime.of(6, 0)).to(LocalTime.of(14, 0)).needs(1).category("MORNING"))
                .constrain(forbidSequence("NIGHT", "MORNING"))
                .constrain(oneShiftPerEmployeePerDay())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(roster.isFeasible()).isTrue();
        for (ShiftAssignment n : roster.assignments()) {
            if (!n.shift().equals("N")) continue;
            assertThat(roster.staffOf("M", n.date().plusDays(1)))
                    .as("%s worked a night on %s", n.employee(), n.date()).doesNotContain(n.employee());
        }
    }

    // ── invariant validator on a two-week instance with everything on ────────

    @Test
    void twoWeekRosterSatisfiesEveryDeclaredRule() {
        int maxHours = 32, maxShifts = 4, maxConsecutive = 3;
        Duration rest = Duration.ofHours(10);
        var builder = Optimize.scheduling().horizon(MON, 14)
                .employee("a", e -> e.skills("RN").costPerHour(10).maxHoursPerWeek(maxHours).prefersShift("M"))
                .employee("b", e -> e.skills("RN").costPerHour(11).maxHoursPerWeek(maxHours))
                .employee("c", e -> e.skills("RN", "ICU").costPerHour(12).maxHoursPerWeek(maxHours))
                .employee("d", e -> e.skills("RN").costPerHour(13).maxHoursPerWeek(maxHours).availableOn(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
                .employee("e", e -> e.skills("RN", "ICU").costPerHour(14).maxHoursPerWeek(maxHours).unavailableOn(MON.plusDays(3)))
                .shift("M", s -> s.daily().from(LocalTime.of(6, 0)).to(LocalTime.of(14, 0)).needs(2).needsAtLeast(1).withSkill("RN").category("MORNING"))
                .shift("N", s -> s.daily().from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN").category("NIGHT"))
                .shift("ICU", s -> s.every(DayOfWeek.MONDAY, DayOfWeek.THURSDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("ICU"))
                .assign("c", "ICU", MON)
                .forbid("a", "N")
                .constrain(oneShiftPerEmployeePerDay())
                .constrain(minRestBetweenShifts(rest))
                .constrain(maxShiftsPerWeek(maxShifts))
                .constrain(maxConsecutiveWorkDays(maxConsecutive))
                .constrain(forbidSequence("NIGHT", "MORNING"))
                .prefer(honorEmployeePreferences(5))
                .prefer(penalizeUnderstaffing(200))
                .prefer(balanceWorkloadAcrossEmployees(1))
                .goal(totalLaborCost());
        EmployeeRoster roster = builder.solver().timeLimit(Duration.ofSeconds(30)).workers(8).solve();
        assertThat(roster.isFeasible()).isTrue();

        // Locks and eligibility.
        assertThat(roster.staffOf("ICU", MON)).containsExactly("c");
        assertThat(roster.shiftsFor("a")).doesNotContain("N");
        for (ShiftAssignment x : roster.assignments()) {
            if (x.shift().equals("ICU")) assertThat(x.employee()).isIn("c", "e");
            if (x.employee().equals("d")) assertThat(x.date().getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            if (x.employee().equals("e")) assertThat(x.date()).isNotEqualTo(MON.plusDays(3));
        }
        // Staffing: N and ICU exact, M between 1 and 2.
        for (int d = 0; d < 14; d++) {
            LocalDate date = MON.plusDays(d);
            assertThat(roster.staffOf("N", date)).hasSize(1);
            assertThat(roster.staffOf("M", date).size()).isBetween(1, 2);
        }
        // Per-employee rules.
        Map<String, List<ShiftAssignment>> byEmp = new HashMap<>();
        for (ShiftAssignment x : roster.assignments()) byEmp.computeIfAbsent(x.employee(), k -> new ArrayList<>()).add(x);
        for (var entry : byEmp.entrySet()) {
            List<ShiftAssignment> xs = entry.getValue();
            long[] minutesByDay = new long[14];
            int[] countByDay = new int[14];
            for (ShiftAssignment x : xs) {
                int day = (int) (x.date().toEpochDay() - MON.toEpochDay());
                long dur = Duration.between(x.start(), x.end()).toMinutes();
                if (dur <= 0) dur += 24 * 60;
                minutesByDay[day] += dur;
                countByDay[day]++;
                assertThat(countByDay[day]).as("one shift per day for %s", entry.getKey()).isEqualTo(1);
            }
            for (int w = 0; w + 7 <= 14; w++) {
                long hours = 0; int shifts = 0;
                for (int d = w; d < w + 7; d++) { hours += minutesByDay[d]; shifts += countByDay[d]; }
                assertThat(hours / 60).as("rolling hours for %s from day %d", entry.getKey(), w).isLessThanOrEqualTo(maxHours);
                assertThat(shifts).as("rolling shifts for %s from day %d", entry.getKey(), w).isLessThanOrEqualTo(maxShifts);
            }
            int run = 0;
            for (int d = 0; d < 14; d++) { run = countByDay[d] > 0 ? run + 1 : 0; assertThat(run).isLessThanOrEqualTo(maxConsecutive); }
            // Rest: absolute start/end pairs.
            for (ShiftAssignment p : xs) for (ShiftAssignment q : xs) {
                if (p == q) continue;
                long pEnd = abs(p, true), qStart = abs(q, false);
                long gap = qStart - pEnd;
                if (gap >= 0) assertThat(gap).as("rest between %s and %s for %s", p, q, entry.getKey()).isGreaterThanOrEqualTo(rest.toMinutes());
            }
            // No morning after a night.
            for (ShiftAssignment n : xs) {
                if (!n.shift().equals("N")) continue;
                assertThat(xs).noneMatch(m -> m.shift().equals("M") && m.date().equals(n.date().plusDays(1)));
            }
        }
    }

    private static long abs(ShiftAssignment a, boolean end) {
        long day = a.date().toEpochDay() - MON.toEpochDay();
        long start = day * 1440 + a.start().toSecondOfDay() / 60;
        if (!end) return start;
        long dur = Duration.between(a.start(), a.end()).toMinutes();
        if (dur <= 0) dur += 1440;
        return start + dur;
    }
}

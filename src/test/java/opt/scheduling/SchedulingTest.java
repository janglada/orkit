package opt.scheduling;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulingTest {

    /** Horizon starting on a Monday — conveniently covers every DayOfWeek once. */
    private static final LocalDate MON = LocalDate.of(2026, 7, 6);

    // ── T-S1: Skill gate enforced ───────────────────────────────────────────

    @Test
    void onlyIcuQualifiedNurseAssignedToIcuShift() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN", "ICU").costPerHour(50))
                .employee("bob",   e -> e.skills("RN").costPerHour(40))
                .shift("mon-icu", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                        .needs(1).withSkill("ICU"))
                .constrain(oneShiftPerEmployeePerDay())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.assignments()).hasSize(1);
        assertThat(roster.staffOf("mon-icu")).containsExactly("alice");
    }

    // ── T-S2: Availability blocks assignment ────────────────────────────────

    @Test
    void employeeUnavailableOnShiftDayProducesInfeasible() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                // Employee is only available on Tuesday, shift is on Monday
                .employee("alice", e -> e.skills("RN").availableOn(DayOfWeek.TUESDAY))
                .shift("mon-day", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                        .needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-S3: minRestBetweenShifts blocks same-day conflicting pair ─────────

    @Test
    void minRestBetweenShiftsForbidsBackToBackAssignment() {
        // Day shift ends 15:00, evening shift starts 19:00 → gap = 4h < 11h
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .employee("bob",   e -> e.skills("RN"))
                .shift("day",     s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                        .needs(1).withSkill("RN"))
                .shift("evening", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(19, 0)).to(LocalTime.of(23, 0))
                        .needs(1).withSkill("RN"))
                .constrain(minRestBetweenShifts(Duration.ofHours(11)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // With min-rest enforced, no single employee can hold both shifts
        String dayStaff     = roster.staffOf("day").get(0);
        String eveningStaff = roster.staffOf("evening").get(0);
        assertThat(dayStaff).isNotEqualTo(eveningStaff);
    }

    // ── T-S4: maxConsecutiveWorkDays ────────────────────────────────────────

    @Test
    void maxConsecutiveWorkDaysRespectsWindowConstraint() {
        // 5 days, 1 shift per day needing 1 person, 3 employees, max 3 consecutive
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("a", e -> e.skills("X"))
                .employee("b", e -> e.skills("X"))
                .employee("c", e -> e.skills("X"))
                .shift("s1", s -> s.on(DayOfWeek.MONDAY)    .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("X"))
                .shift("s2", s -> s.on(DayOfWeek.TUESDAY)   .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("X"))
                .shift("s3", s -> s.on(DayOfWeek.WEDNESDAY) .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("X"))
                .shift("s4", s -> s.on(DayOfWeek.THURSDAY)  .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("X"))
                .shift("s5", s -> s.on(DayOfWeek.FRIDAY)    .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("X"))
                .constrain(maxConsecutiveWorkDays(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // No employee may work 4+ consecutive days
        List<String> shiftOrder = List.of("s1", "s2", "s3", "s4", "s5");
        for (String emp : List.of("a", "b", "c")) {
            List<String> empShifts = roster.shiftsFor(emp);
            int consecutive = 0;
            for (String s : shiftOrder) {
                if (empShifts.contains(s)) {
                    consecutive++;
                    assertThat(consecutive)
                            .as("%s has more than 3 consecutive shifts", emp)
                            .isLessThanOrEqualTo(3);
                } else {
                    consecutive = 0;
                }
            }
        }
    }

    // ── T-S5: minimizeTotalLaborCost picks cheaper employee ─────────────────

    @Test
    void laborCostObjectivePicksCheaperEmployee() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("expensive", e -> e.skills("RN").costPerHour(100))
                .employee("cheap",     e -> e.skills("RN").costPerHour(30))
                .shift("mon-day", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon-day")).containsExactly("cheap");
        // Objective = 8h × 60min × 30$/h = 14400
        assertThat(roster.objective()).hasValue(8 * 60 * 30.0);
    }

    // ── T-S6: oneShiftPerEmployeePerDay prevents double booking ─────────────

    @Test
    void oneShiftPerEmployeePerDayPreventsDoubleBooking() {
        // 1 employee, 2 shifts same day, each needs 1 person → infeasible with this rule
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("morning", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(11, 0)).needs(1).withSkill("RN"))
                .shift("afternoon", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(13, 0)).to(LocalTime.of(17, 0)).needs(1).withSkill("RN"))
                .constrain(oneShiftPerEmployeePerDay())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-S7: maxHoursPerWeek enforced ──────────────────────────────────────

    @Test
    void maxHoursPerWeekLimitsAssignments() {
        // Each shift is 8h. Employee capped at 16h/week (so max 2 shifts).
        // 3 shifts available, only 2 other employees exist as backup.
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("capped", e -> e.skills("RN").maxHoursPerWeek(16))
                .employee("other1", e -> e.skills("RN"))
                .employee("other2", e -> e.skills("RN"))
                .shift("s1", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("s2", s -> s.on(DayOfWeek.TUESDAY)  .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("s3", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // "capped" employee can hold at most 2 shifts (16h / 8h)
        assertThat(roster.shiftsFor("capped").size()).isLessThanOrEqualTo(2);
    }

    // ── T-S8: No qualified employee → infeasible ────────────────────────────

    @Test
    void noQualifiedEmployeeForRequiredSkillIsInfeasible() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("surgery", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("SURGERY"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-S9: ModelException on missing horizon ──────────────────────────────

    @Test
    void missingHorizonThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .employee("alice", e -> e.skills("RN"))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY)
                                .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .solver()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("horizon");
    }

    // ── T-S10: ModelException on duplicate employee id ──────────────────────

    @Test
    void duplicateEmployeeIdThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN"))
                        .employee("alice", e -> e.skills("ICU"))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY)
                                .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .solver()
                        .solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("alice");
    }

    // ── T-S11: Preference soft goal honored ─────────────────────────────────

    @Test
    void honorPreferencesAssignedPreferredShiftWhenPossible() {
        // alice prefers "day". With only one spot available on day, alice should get it.
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").prefersShift("day"))
                .employee("bob",   e -> e.skills("RN"))
                .shift("day",   s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0)).needs(1).withSkill("RN"))
                .shift("night", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0)).needs(1).withSkill("RN"))
                .prefer(honorEmployeePreferences())
                .solver().timeLimit(Duration.ofSeconds(5)).seed(42).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("day")).containsExactly("alice");
    }

    // ── T-S12: Reusable problem — same problem, two solver configs ───────────

    @Test
    void reusableProblemProducesSameOptimalResult() {
        var problem = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(50))
                .employee("bob",   e -> e.skills("RN").costPerHour(30))
                .shift("s", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .build();

        EmployeeRoster r1 = problem.solver().timeLimit(Duration.ofSeconds(3)).solve();
        EmployeeRoster r2 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(r1.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(r2.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(r1.objective()).isEqualTo(r2.objective());
        assertThat(r1.staffOf("s")).isEqualTo(r2.staffOf("s"));
    }
}

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

    // ── T-S13: minRest correctly crosses day boundary (regression for tooCloseWith bug) ──

    @Test
    void minRestAllowsAssignmentsOnNonAdjacentDays() {
        // Mon night 23:00–07:00 ends on Tue at 07:00.
        // Wed day 07:00–15:00 starts 48 h later — well above the 11 h threshold.
        // Before the fix, tooCloseWith() used only clock time and would block this.
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("mon-night", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0)).needs(1).withSkill("RN"))
                .shift("wed-day",   s -> s.on(DayOfWeek.WEDNESDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0)).needs(1).withSkill("RN"))
                .constrain(minRestBetweenShifts(Duration.ofHours(11)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        // Both shifts are far apart; alice must be assigned to both (she's the only employee).
        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.shiftsFor("alice")).containsExactlyInAnyOrder("mon-night", "wed-day");
    }

    // ── T-S14: maxConsecutiveWorkDays correct over a 14-day (two-week) horizon ──

    @Test
    void maxConsecutiveWorkDaysCorrectOverMultiWeekHorizon() {
        // 14-day horizon, 3 employees, 14 shifts (one per day), max consecutive 5.
        // With the old DayOfWeek-keyed map, week-1 Monday and week-2 Monday shared a slot
        // and the constraint was silently wrong. Now each calendar day has its own slot.
        LocalDate twoWeek = MON;
        var builder = Optimize.scheduling().horizon(twoWeek, 14);

        builder.employee("a", e -> e.skills("X"))
               .employee("b", e -> e.skills("X"))
               .employee("c", e -> e.skills("X"));

        DayOfWeek[] dow = DayOfWeek.values(); // MON..SUN (1..7)
        String[] names = {"s01","s02","s03","s04","s05","s06","s07",
                          "s08","s09","s10","s11","s12","s13","s14"};
        for (int i = 0; i < 14; i++) {
            final int day = i;
            builder.shift(names[i], s -> s
                    .on(twoWeek.plusDays(day))
                    .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                    .needs(1).withSkill("X"));
        }
        builder.constrain(maxConsecutiveWorkDays(5));

        EmployeeRoster roster = builder.solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // Verify no employee works 6+ consecutive shifts
        for (String emp : List.of("a", "b", "c")) {
            List<String> empShifts = roster.shiftsFor(emp);
            int consecutive = 0;
            for (String name : names) {
                if (empShifts.contains(name)) {
                    consecutive++;
                    assertThat(consecutive)
                            .as("%s exceeded maxConsecutiveWorkDays(5)", emp)
                            .isLessThanOrEqualTo(5);
                } else {
                    consecutive = 0;
                }
            }
        }
    }

    // ── T-S15: Structural — staffOf and shiftsFor are inverses ──────────────

    @Test
    void staffOfAndShiftsForAreInverses() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(30))
                .employee("bob",   e -> e.skills("RN").costPerHour(40))
                .employee("carol", e -> e.skills("RN", "ICU").costPerHour(50))
                .shift("mon-rn",  s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("tue-icu", s -> s.on(DayOfWeek.TUESDAY)  .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("ICU"))
                .shift("wed-rn",  s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();

        // Forward: every shift in shiftsFor(e) must list e in staffOf(shift)
        for (String emp : List.of("alice", "bob", "carol")) {
            for (String shift : roster.shiftsFor(emp)) {
                assertThat(roster.staffOf(shift))
                        .as("Employee %s appears in shiftsFor but not staffOf(%s)", emp, shift)
                        .contains(emp);
            }
        }

        // Reverse: every employee in staffOf(s) must list s in shiftsFor(employee)
        for (String shift : List.of("mon-rn", "tue-icu", "wed-rn")) {
            for (String emp : roster.staffOf(shift)) {
                assertThat(roster.shiftsFor(emp))
                        .as("Employee %s appears in staffOf but not shiftsFor(%s)", emp, shift)
                        .contains(shift);
            }
        }
    }

    // ── T-S16: needs(2) staffs exactly 2 employees on a shift ───────────────

    @Test
    void needsTwoStaffsExactlyTwoPeople() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .employee("bob",   e -> e.skills("RN"))
                .employee("carol", e -> e.skills("RN"))
                .shift("big-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(2).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("big-shift")).hasSize(2);
    }

    // ── T-S17: needs(2) with only 1 qualified employee → infeasible ─────────

    @Test
    void needsTwoWithOnlyOneQualifiedIsInfeasible() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("ICU"))
                .employee("bob",   e -> e.skills("RN"))  // lacks ICU
                .shift("icu-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(2).withSkill("ICU"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-S18: Objective equals sum of cost contributions ───────────────────

    @Test
    void objectiveEqualsExactCostSum() {
        // alice (cost=30) → 8h shift, bob (cost=20) → 4h shift
        // objective = (8*60*30) + (4*60*20) = 14400 + 4800 = 19200
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(30))
                .employee("bob",   e -> e.skills("LPN").costPerHour(20))
                .shift("rn-8h",  s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("lpn-4h", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(12, 0)).needs(1).withSkill("LPN"))
                .minimize(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.objective()).hasValue(8 * 60 * 30.0 + 4 * 60 * 20.0);
    }

    // ── T-S19: Employee without constraints works shifts on two different days

    @Test
    void singleEmployeeCanWorkShiftsOnDifferentDays() {
        // No rest rule, no oneShiftPerDay — employee is free to work both
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.shiftsFor("alice")).containsExactlyInAnyOrder("mon", "wed");
    }

    // ── T-S20: Overnight shift duration counted correctly for maxHoursPerWeek

    @Test
    void overnightShiftDurationCountedCorrectly() {
        // 23:00–07:00 = 8 hours; employee with maxHoursPerWeek=8 should work exactly 1
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").maxHoursPerWeek(8))
                .employee("bob",   e -> e.skills("RN"))
                .shift("night", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0)).needs(1).withSkill("RN"))
                .shift("day",   s -> s.on(DayOfWeek.TUESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // alice is capped at 8h; the overnight shift is exactly 8h, so she can work it,
        // but then she cannot work the Tuesday day shift (would exceed 8h total).
        assertThat(roster.shiftsFor("alice").size()).isLessThanOrEqualTo(1);
    }

    // ── T-S21: Duplicate shift id throws ModelException ─────────────────────

    @Test
    void duplicateShiftIdThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN"))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .shift("s", s -> s.on(DayOfWeek.TUESDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("s");
    }

    // ── T-S22: Shift missing .on() throws ModelException at builder time ─────

    @Test
    void shiftMissingDayThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN"))
                        .shift("s", s -> s.from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("s");
    }

    // ── T-S23: Shift missing .from() throws ModelException at builder time ───

    @Test
    void shiftMissingFromThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN"))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY).to(LocalTime.of(16, 0)).needs(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("s");
    }

    // ── T-S24: needs(0) throws ModelException ───────────────────────────────

    @Test
    void needsZeroThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN"))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY)
                                .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(0))
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-S25: costPerHour(-1) throws ModelException ─────────────────────────

    @Test
    void negativeCostPerHourThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN").costPerHour(-1))
                        .shift("s", s -> s.on(DayOfWeek.MONDAY)
                                .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("-1");
    }

    // ── T-S26: runtime() is non-negative ─────────────────────────────────────

    @Test
    void runtimeIsNonNegative() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("s", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.runtime().isNegative()).isFalse();
    }

    // ── T-S27: Employee with multiple skills covers both ICU and RN shifts ───

    @Test
    void multiSkillEmployeeCoversBothShiftTypes() {
        // carol has both ICU and RN; she's the only one who can cover the ICU shift;
        // alice (RN only) covers the RN shift.
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(30))
                .employee("carol", e -> e.skills("RN", "ICU").costPerHour(50))
                .shift("icu-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("ICU"))
                .shift("rn-shift",  s -> s.on(DayOfWeek.TUESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("icu-shift")).containsExactly("carol"); // only one qualified
        assertThat(roster.staffOf("rn-shift")).containsExactly("alice");  // cheaper
    }

    // ── T-S28: All assignments have non-null date, employee, and shift ────────

    @Test
    void allAssignmentFieldsAreNonNull() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN"))
                .shift("s", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        for (ShiftAssignment a : roster.assignments()) {
            assertThat(a.employee()).isNotNull();
            assertThat(a.shift()).isNotNull();
            assertThat(a.date()).isNotNull();
            assertThat(a.start()).isNotNull();
            assertThat(a.end()).isNotNull();
        }
    }

    // ── T-S29: Tunable preference weight overrides vs default cost-dominant behavior ──

    @Test
    void weightedPreferenceOverridesLaborCost() {
        // bob costs 10× alice but prefers the shift. A huge preference weight (100_000)
        // makes the reward outweigh bob's higher labor cost; the default weight (5) does not.
        // bob cost = 8h*60*100 = 48000; alice = 8h*60*10 = 4800; cost delta = 43200.
        // weight 100_000 → bob net = 48000 - 100000 = -52000 < alice 4800  (bob wins)
        // weight 5       → bob net = 48000 - 5     = 47995 > alice 4800  (alice wins)
        EmployeeRoster preferred = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(10))
                .employee("bob",   e -> e.skills("RN").costPerHour(100).prefersShift("s"))
                .shift("s", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .prefer(honorEmployeePreferences(100_000))
                .solver().timeLimit(Duration.ofSeconds(5)).seed(1).solve();

        assertThat(preferred.isFeasible()).isTrue();
        assertThat(preferred.staffOf("s")).containsExactly("bob");

        EmployeeRoster costDriven = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(10))
                .employee("bob",   e -> e.skills("RN").costPerHour(100).prefersShift("s"))
                .shift("s", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .minimize(totalLaborCost())
                .prefer(honorEmployeePreferences())
                .solver().timeLimit(Duration.ofSeconds(5)).seed(1).solve();

        assertThat(costDriven.isFeasible()).isTrue();
        assertThat(costDriven.staffOf("s")).containsExactly("alice");
    }

    // ── T-S30: maxShiftsPerWeek caps assignments in any rolling 7-day window ──

    @Test
    void maxShiftsPerWeekCapsRollingWindow() {
        // 14-day horizon, 5 employees, one day shift per day (needs 1), at most 2 shifts
        // per any rolling 7-day window per employee. 14 assignments spread over 5 people
        // is feasible (5 × 2 = 10 ≥ 7 per week), but the rolling window makes spreading tight.
        LocalDate twoWeek = MON;
        var builder = Optimize.scheduling().horizon(twoWeek, 14);
        builder.employee("a", e -> e.skills("X"))
               .employee("b", e -> e.skills("X"))
               .employee("c", e -> e.skills("X"))
               .employee("d", e -> e.skills("X"))
               .employee("e", e -> e.skills("X"));
        String[] names = new String[14];
        for (int i = 0; i < 14; i++) {
            names[i] = "s" + i;
            final int day = i;
            builder.shift(names[i], s -> s
                    .on(twoWeek.plusDays(day))
                    .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                    .needs(1).withSkill("X"));
        }
        builder.constrain(maxShiftsPerWeek(2));

        EmployeeRoster roster = builder.solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // For each employee, every 7-day rolling window must hold ≤ 2 of their shifts.
        for (String emp : List.of("a", "b", "c", "d", "e")) {
            List<Integer> dayIndices = roster.shiftsFor(emp).stream()
                    .map(name -> Integer.parseInt(name.substring(1)))
                    .sorted()
                    .toList();
            for (int start = 0; start + 7 <= 14; start++) {
                int lo = start, hi = start + 7;
                long count = dayIndices.stream().filter(d -> d >= lo && d < hi).count();
                assertThat(count)
                        .as("%s has %d shifts in days [%d,%d) — maxShiftsPerWeek(2) violated", emp, count, lo, hi)
                        .isLessThanOrEqualTo(2);
            }
        }
    }

    // ── T-S31: maxConsecutiveNightShifts limits consecutive overnight shifts ───

    @Test
    void maxConsecutiveNightShiftsCapsConsecutiveOvernights() {
        // 5 consecutive overnight shifts (22:00–06:00, spanning midnight), 2 employees,
        // at most 2 consecutive nights each. Feasible by alternating pairs (a a b b a).
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("a", e -> e.skills("RN"))
                .employee("b", e -> e.skills("RN"))
                .shift("n1", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n2", s -> s.on(DayOfWeek.TUESDAY)  .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n3", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n4", s -> s.on(DayOfWeek.THURSDAY) .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n5", s -> s.on(DayOfWeek.FRIDAY)   .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .constrain(maxConsecutiveNightShifts(2))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // No employee may work 3+ consecutive nights.
        List<String> order = List.of("n1", "n2", "n3", "n4", "n5");
        for (String emp : List.of("a", "b")) {
            List<String> nights = roster.shiftsFor(emp);
            int consecutive = 0;
            for (String s : order) {
                if (nights.contains(s)) {
                    consecutive++;
                    assertThat(consecutive)
                            .as("%s worked more than 2 consecutive night shifts", emp)
                            .isLessThanOrEqualTo(2);
                } else {
                    consecutive = 0;
                }
            }
        }
    }

    // ── T-S32: maxConsecutiveNightShifts infeasible when impossible ───────────

    @Test
    void maxConsecutiveNightShiftsInfeasibleWhenImpossible() {
        // One employee, 3 consecutive nights, cap of 2 → cannot satisfy all three shifts.
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("a", e -> e.skills("RN"))
                .shift("n1", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n2", s -> s.on(DayOfWeek.TUESDAY)  .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .shift("n3", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .constrain(maxConsecutiveNightShifts(2))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }
}

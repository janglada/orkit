package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for date-specific employee unavailability:
 *   EmployeeConfig.unavailableOn(LocalDate...)
 *   EmployeeConfig.onHoliday(LocalDate, LocalDate)
 */
class UnavailabilityTest {

    // Horizon: Mon 2026-07-06 → Sun 2026-07-12
    private static final LocalDate MON = LocalDate.of(2026, 7, 6);
    private static final LocalDate TUE = LocalDate.of(2026, 7, 7);
    private static final LocalDate WED = LocalDate.of(2026, 7, 8);
    private static final LocalDate THU = LocalDate.of(2026, 7, 9);
    private static final LocalDate FRI = LocalDate.of(2026, 7, 10);
    private static final LocalDate SAT = LocalDate.of(2026, 7, 11);
    private static final LocalDate SUN = LocalDate.of(2026, 7, 12);

    // ── Single date ─────────────────────────────────────────────────────────

    @Test
    void singleBlockedDateExcludesEmployeeFromThatShift() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").unavailableOn(MON))
                .employee("bob",   e -> e.skills("RN"))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon-shift")).containsExactly("bob");
    }

    @Test
    void blockedDateDoesNotAffectOtherDays() {
        // alice (cheaper) is blocked only on Monday; she wins Tuesday by cost
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(10).unavailableOn(MON))
                .employee("bob",   e -> e.skills("RN").costPerHour(50))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .shift("tue-shift", s -> s.on(DayOfWeek.TUESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon-shift")).doesNotContain("alice"); // date-blocked
        assertThat(roster.staffOf("tue-shift")).containsExactly("alice"); // cheaper, unblocked
    }

    @Test
    void onlyEmployeeBlockedOnShiftDayMakesItInfeasible() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").unavailableOn(MON))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    @Test
    void multipleBlockedDatesAllRespected() {
        // alice (cheaper) blocked Mon+Wed; cost objective forces her onto Tuesday
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(10).unavailableOn(MON, WED))
                .employee("bob",   e -> e.skills("RN").costPerHour(50))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .shift("tue-shift", s -> s.on(DayOfWeek.TUESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .shift("wed-shift", s -> s.on(DayOfWeek.WEDNESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                        .needs(1).withSkill("RN"))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon-shift")).doesNotContain("alice"); // date-blocked
        assertThat(roster.staffOf("wed-shift")).doesNotContain("alice"); // date-blocked
        assertThat(roster.staffOf("tue-shift")).containsExactly("alice"); // cheaper, unblocked
    }

    // ── Holiday ranges ───────────────────────────────────────────────────────

    @Test
    void holidayBlocksEntireRange() {
        // alice on holiday Mon-Wed; bob must cover all three days
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").onHoliday(MON, WED))
                .employee("bob",   e -> e.skills("RN"))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("tue-shift", s -> s.on(DayOfWeek.TUESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("wed-shift", s -> s.on(DayOfWeek.WEDNESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon-shift")).doesNotContain("alice");
        assertThat(roster.staffOf("tue-shift")).doesNotContain("alice");
        assertThat(roster.staffOf("wed-shift")).doesNotContain("alice");
    }

    @Test
    void holidayDayAfterRangeIsAvailable() {
        // alice on holiday Mon-Wed; Thu is outside the range — she can work it
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").onHoliday(MON, WED))
                .employee("bob",   e -> e.skills("RN"))
                .shift("thu-shift", s -> s.on(DayOfWeek.THURSDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        // Both alice and bob are eligible for Thursday
        assertThat(roster.staffOf("thu-shift")).hasSize(1);
    }

    @Test
    void singleDayHolidayEquivalentToUnavailableOn() {
        // onHoliday(d, d) is the same as unavailableOn(d)
        EmployeeRoster rosterA = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").unavailableOn(WED))
                .employee("bob",   e -> e.skills("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        EmployeeRoster rosterB = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").onHoliday(WED, WED))
                .employee("bob",   e -> e.skills("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(rosterA.staffOf("wed")).isEqualTo(rosterB.staffOf("wed"));
    }

    @Test
    void fullWeekHolidayMakesEmployeeEntirelyUnavailable() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").onHoliday(MON, SUN)) // entire horizon
                .employee("bob",   e -> e.skills("RN"))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("fri-shift", s -> s.on(DayOfWeek.FRIDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.shiftsFor("alice")).isEmpty();
        assertThat(roster.shiftsFor("bob")).containsExactlyInAnyOrder("mon-shift", "fri-shift");
    }

    // ── Interaction with day-of-week availability ────────────────────────────

    @Test
    void blockedDateOverridesAvailableDayOfWeek() {
        // alice is nominally available Mon-Fri, but the specific Monday is blocked
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN")
                        .availableOn(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
                        .unavailableOn(MON))  // that specific Monday is blocked
                .employee("bob",   e -> e.skills("RN"))
                .shift("mon-shift", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon-shift")).containsExactly("bob");
    }

    @Test
    void dayOfWeekUnavailabilityAndDateBlockBothApply() {
        // alice: not available weekends (day-of-week), AND blocked on Friday via date
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN")
                        .availableOn(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        .unavailableOn(FRI))
                .employee("bob", e -> e.skills("RN"))
                .shift("fri-shift", s -> s.on(DayOfWeek.FRIDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("sat-shift", s -> s.on(DayOfWeek.SATURDAY)
                        .from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("fri-shift")).doesNotContain("alice"); // date-blocked
        assertThat(roster.staffOf("sat-shift")).doesNotContain("alice"); // day-of-week blocked
    }

    // ── Multiple employees, partial blocking ─────────────────────────────────

    @Test
    void threeEmployeesEachBlockedOnDifferentDay() {
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").unavailableOn(MON))
                .employee("bob",   e -> e.skills("RN").unavailableOn(TUE))
                .employee("carol", e -> e.skills("RN").unavailableOn(WED))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("tue", s -> s.on(DayOfWeek.TUESDAY)  .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon")).doesNotContain("alice");
        assertThat(roster.staffOf("tue")).doesNotContain("bob");
        assertThat(roster.staffOf("wed")).doesNotContain("carol");
    }

    @Test
    void holidayAndLaborCostObjectiveInteract() {
        // alice is cheaper but on holiday Mon; bob (expensive) must cover Monday
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").costPerHour(20).onHoliday(MON, MON))
                .employee("bob",   e -> e.skills("RN").costPerHour(50))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("tue", s -> s.on(DayOfWeek.TUESDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon")).containsExactly("bob");   // forced by holiday
        assertThat(roster.staffOf("tue")).containsExactly("alice"); // cheaper wins
    }

    @Test
    void holidayCombinedWithMinRestRule() {
        // alice on holiday Mon; works Wed and Thu (no rest violation across those)
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").onHoliday(MON, TUE))
                .employee("bob",   e -> e.skills("RN"))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("thu", s -> s.on(DayOfWeek.THURSDAY) .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .constrain(Rules.minRestBetweenShifts(Duration.ofHours(11)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon")).doesNotContain("alice");
    }

    // ── Multi-week horizon ───────────────────────────────────────────────────

    @Test
    void blockedDateInSecondWeekOfTwoWeekHorizon() {
        // 14-day horizon; alice blocked on the second Monday (2026-07-13)
        LocalDate secondMonday = LocalDate.of(2026, 7, 13);
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 14)
                .employee("alice", e -> e.skills("RN").unavailableOn(secondMonday))
                .employee("bob",   e -> e.skills("RN"))
                // First Monday shift
                .shift("mon1", s -> s.on(DayOfWeek.MONDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        // on(MONDAY) recurs: mon1 occurs on both Mondays of the 14-day horizon.
        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon1")).hasSize(2);
        assertThat(roster.staffOf("mon1", LocalDate.of(2026, 7, 6))).hasSize(1);
        // alice is blocked on the second Monday, so bob must cover it.
        assertThat(roster.staffOf("mon1", secondMonday)).containsExactly("bob");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void holidayEndBeforeStartThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.scheduling()
                        .horizon(MON, 7)
                        .employee("alice", e -> e.skills("RN")
                                .onHoliday(FRI, MON))  // end before start
                        .shift("s", s -> s.on(DayOfWeek.MONDAY)
                                .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("end date");
    }

    @Test
    void unavailableOnOutsideHorizonIsHarmless() {
        // Blocking a date outside the horizon has no effect — should still solve normally
        LocalDate outsideHorizon = LocalDate.of(2025, 1, 1);
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN").unavailableOn(outsideHorizon))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon")).containsExactly("alice");
    }

    @Test
    void overlappingHolidayCallsAreDeduplicated() {
        // Calling unavailableOn with the same date twice should not cause issues
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN")
                        .unavailableOn(MON)
                        .unavailableOn(MON)) // duplicate — must not throw or double-block
                .employee("bob", e -> e.skills("RN"))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(roster.staffOf("mon")).containsExactly("bob");
    }

    @Test
    void holidayRangeAndUnavailableOnCanBeCombined() {
        // alice on holiday Mon-Tue AND separately blocked on Thu
        EmployeeRoster roster = Optimize.scheduling()
                .horizon(MON, 7)
                .employee("alice", e -> e.skills("RN")
                        .onHoliday(MON, TUE)
                        .unavailableOn(THU))
                .employee("bob", e -> e.skills("RN"))
                .shift("mon", s -> s.on(DayOfWeek.MONDAY)   .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("wed", s -> s.on(DayOfWeek.WEDNESDAY).from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .shift("thu", s -> s.on(DayOfWeek.THURSDAY) .from(LocalTime.of(8,0)).to(LocalTime.of(16,0)).needs(1).withSkill("RN"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(roster.isFeasible()).isTrue();
        assertThat(roster.staffOf("mon")).doesNotContain("alice"); // holiday
        assertThat(roster.staffOf("thu")).doesNotContain("alice"); // date-blocked
        // Wed is unblocked for alice — at least one person must cover it
        assertThat(roster.staffOf("wed")).hasSize(1);
    }
}

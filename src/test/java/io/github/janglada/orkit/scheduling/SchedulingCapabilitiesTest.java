package io.github.janglada.orkit.scheduling;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Skill sets and role mixes, labor cost in currency, weekly minimum and contracted hours. */
class SchedulingCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private static ShiftConfig day(ShiftConfig s) {
        return s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0));
    }

    // ── Skills and roles ────────────────────────────────────────────────────

    @Test
    void everyRequiredSkillMustBeHeld() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 1)
                .employee("rn", e -> e.skills("RN").costPerHour(1))
                .employee("icu", e -> e.skills("ICU").costPerHour(1))
                .employee("both", e -> e.skills("RN", "ICU").costPerHour(50))
                .shift("icuDay", s -> day(s).withSkills("RN", "ICU"))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.isOptimal()).isTrue();
        assertThat(roster.staffOf("icuDay")).containsExactly("both");
    }

    @Test
    void roleMixFillsEachRoleWithQualifiedStaff() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 2)
                .employee("charge", e -> e.skills("RN", "CHARGE").costPerHour(40))
                .employee("senior", e -> e.skills("RN", "CHARGE").costPerHour(45))
                .employee("rn1", e -> e.skills("RN").costPerHour(30))
                .employee("rn2", e -> e.skills("RN").costPerHour(30))
                .shift("ward", s -> day(s).needs(3).withSkill("RN").role("CHARGE", 1))
                .constrain(Rules.oneShiftPerEmployeePerDay())
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.isOptimal()).isTrue();
        for (LocalDate date : new LocalDate[]{MONDAY, MONDAY.plusDays(1)}) {
            var on = roster.assignmentsOn(date);
            assertThat(on).hasSize(3);
            assertThat(on).filteredOn(a -> "CHARGE".equals(a.role())).hasSize(1)
                    .allSatisfy(a -> assertThat(a.employee()).isIn("charge", "senior"));
            assertThat(on).filteredOn(a -> a.role() == null).hasSize(2);
        }
        // The cheapest crew is charge + rn1 + rn2 on both days.
        assertThat(roster.laborCost()).isEqualTo(2 * 8 * (40 + 30 + 30));
    }

    @Test
    void roleAndSkillArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.scheduling().horizon(MONDAY, 1)
                .shift("s", s -> day(s).needs(3).needsAtLeast(1).role("CHARGE", 2)))
                .isInstanceOf(ModelException.class).hasMessageContaining("hard minimum is 1");
        assertThatThrownBy(() -> Optimize.scheduling().shift("s", s -> s.role("CHARGE", 0)))
                .isInstanceOf(ModelException.class).hasMessageContaining("role count");
        assertThatThrownBy(() -> Optimize.scheduling().shift("s", s -> s.role(" ", 1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("role skill");
        assertThatThrownBy(() -> Optimize.scheduling().shift("s", s -> s.withSkills("RN", "")))
                .isInstanceOf(ModelException.class).hasMessageContaining("skill");
    }

    @Test
    void aRoleNobodyCanFillIsInfeasible() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 1)
                .employee("a", e -> e.skills("RN"))
                .shift("s", s -> day(s).role("CHARGE", 1))
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── Cost and hours ──────────────────────────────────────────────────────

    @Test
    void laborCostAndHoursAreReportedInPlainUnits() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 1)
                .employee("a", e -> e.costPerHour(12))
                .shift("night", s -> s.on(MONDAY).from(LocalTime.of(22, 0)).to(LocalTime.of(6, 30)))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(LIMIT).solve();
        ShiftAssignment a = roster.assignments().get(0);
        assertThat(a.minutes()).isEqualTo(510);
        assertThat(a.cost()).isEqualTo(102.0);
        assertThat(roster.laborCost()).isEqualTo(102.0);
        assertThat(roster.hoursOf("a")).isEqualTo(8.5);
        assertThat(roster.hoursOf("nobody")).isZero();
        assertThat(roster.objective()).as("the objective stays in cost-per-hour-times-minutes units").hasValue(12 * 510);
    }

    @Test
    void weeklyMinimumOverridesCost() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 10)
                .employee("cheap", e -> e.costPerHour(10))
                .employee("pricey", e -> e.costPerHour(30).minHoursPerWeek(16))
                .shift("day", s -> day(s))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.isOptimal()).isTrue();
        // Two shifts in the first full week; the trailing 3-day partial week is not constrained.
        long firstWeek = roster.assignments().stream()
                .filter(a -> a.employee().equals("pricey") && a.date().isBefore(MONDAY.plusDays(7))).count();
        assertThat(firstWeek).isEqualTo(2);
        assertThat(roster.hoursOf("pricey")).isEqualTo(16.0);
    }

    @Test
    void weeklyMinimumCanMakeTheModelInfeasible() {
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 7)
                .employee("a", e -> e.minHoursPerWeek(60))
                .shift("day", s -> day(s))
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    @Test
    void contractHoursSplitTheWeek() {
        // 56 hours of work, contracts of 24 and 32 hours: the exact split costs nothing extra.
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 7)
                .employee("part", e -> e.costPerHour(20).contractHoursPerWeek(24))
                .employee("full", e -> e.costPerHour(20).contractHoursPerWeek(32))
                .shift("day", s -> day(s))
                .prefer(Goals.penalizeContractDeviation(10))
                .goal(Goals.totalLaborCost())
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.isOptimal()).isTrue();
        assertThat(roster.hoursOf("part")).isEqualTo(24.0);
        assertThat(roster.hoursOf("full")).isEqualTo(32.0);
        assertThat(roster.objective()).hasValue(20 * 56 * 60);
    }

    @Test
    void contractHoursApplyWithTheDefaultWeight() {
        // Without the goal declared, the default weight still pulls toward the contract.
        EmployeeRoster roster = Optimize.scheduling().horizon(MONDAY, 7)
                .employee("a", e -> e.contractHoursPerWeek(16))
                .employee("b", e -> e.contractHoursPerWeek(40))
                .shift("day", s -> day(s))
                .solver().timeLimit(LIMIT).solve();
        assertThat(roster.isOptimal()).isTrue();
        assertThat(roster.hoursOf("a")).isEqualTo(16.0);
        assertThat(roster.hoursOf("b")).isEqualTo(40.0);
        assertThat(roster.objective()).hasValue(0);
    }

    @Test
    void hourArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.scheduling().employee("a", e -> e.minHoursPerWeek(0)))
                .isInstanceOf(ModelException.class).hasMessageContaining("minHoursPerWeek");
        assertThatThrownBy(() -> Optimize.scheduling().employee("a", e -> e.contractHoursPerWeek(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("contractHoursPerWeek");
        assertThatThrownBy(() -> Optimize.scheduling().employee("a", e -> e.maxHoursPerWeek(20).minHoursPerWeek(30)))
                .isInstanceOf(ModelException.class).hasMessageContaining("exceeds maxHoursPerWeek");
        assertThatThrownBy(() -> Goals.penalizeContractDeviation(0))
                .isInstanceOf(ModelException.class).hasMessageContaining("contract deviation");
        assertThat(Goals.penalizeContractDeviation()).isEqualTo(new SoftGoal.PenalizeContractDeviation(1));
    }
}

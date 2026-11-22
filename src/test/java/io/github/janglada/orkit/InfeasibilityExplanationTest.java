package io.github.janglada.orkit;

import io.github.janglada.orkit.scheduling.Rules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CpSatSolver#explainInfeasibility(boolean)} names the constraint groups that conflict. */
class InfeasibilityExplanationTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private static List<String> reasons(Solution s) {
        assertThat(s.status()).isEqualTo(SolveStatus.INFEASIBLE);
        return s.diagnostics().infeasibilityReasons();
    }

    @Test
    void schedulingNamesTheUnderstaffedShift() {
        var problem = Optimize.scheduling().horizon(MONDAY, 2)
                .employee("a", e -> e.skills("RN"))
                .employee("b", e -> e.skills("LPN"))
                .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(2).withSkill("RN"))
                .build();
        assertThat(reasons(problem.solver().timeLimit(LIMIT).solve())).as("off by default").isEmpty();
        List<String> why = reasons(problem.solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(why).isNotEmpty().allMatch(r -> r.startsWith("staffing: shift 'day'"));
        assertThat(why.get(0)).contains("needs 2 qualified employee(s)");
    }

    @Test
    void schedulingNamesConflictingRulesHoursAndLocks() {
        List<String> hours = reasons(Optimize.scheduling().horizon(MONDAY, 2)
                .employee("a", e -> e.maxHoursPerWeek(8))
                .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(hours).contains("maxHoursPerWeek(8) of 'a'");
        assertThat(hours).anyMatch(r -> r.startsWith("staffing: shift 'day'"));

        List<String> locks = reasons(Optimize.scheduling().horizon(MONDAY, 1)
                .employee("a", e -> {})
                .employee("b", e -> {})
                .shift("day", s -> s.on(MONDAY).from(LocalTime.of(6, 0)).to(LocalTime.of(14, 0)))
                .shift("late", s -> s.on(MONDAY).from(LocalTime.of(14, 0)).to(LocalTime.of(22, 0)))
                .assign("a", "day").assign("a", "late")
                .constrain(Rules.oneShiftPerEmployeePerDay())
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(locks).containsExactlyInAnyOrder(
                "assign 'a' on shift 'day' on 2026-07-06",
                "assign 'a' on shift 'late' on 2026-07-06",
                "oneShiftPerEmployeePerDay for 'a'");

        List<String> minimum = reasons(Optimize.scheduling().horizon(MONDAY, 7)
                .employee("a", e -> e.minHoursPerWeek(16).skills("X"))
                .shift("day", s -> s.on(MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).withSkill("X"))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(minimum).containsExactly("minHoursPerWeek(16) of 'a' in week 1");

        List<String> roles = reasons(Optimize.scheduling().horizon(MONDAY, 1)
                .employee("a", e -> e.skills("RN"))
                .shift("day", s -> s.on(MONDAY).from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).role("CHARGE", 1))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(roles).contains("1 x role 'CHARGE' on shift 'day' on 2026-07-06");
    }

    @Test
    void jobShopNamesDeadlinesOrderAndPrecedences() {
        List<String> deadline = reasons(Optimize.jobShop().machines("M1", "M2")
                .job("J", j -> j.operation().on("M1").takes(5).then().on("M2").takes(5).deadlineAt(8))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(deadline).contains("deadline 8 of operation 1 of job 'J'", "operation order of job 'J'");

        List<String> precedence = reasons(Optimize.jobShop().machines("M")
                .job("A", j -> j.operation().on("M").takes(5).releaseAt(3))
                .job("B", j -> j.operation().on("M").takes(5).deadlineAt(9).then().on("M").takes(1).maxLagAfterPrevious(0))
                .precedence("A", "B")
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(precedence).contains("precedence: operation 0 of 'A' before operation 0 of 'B'",
                "deadline 9 of operation 0 of job 'B'", "release 3 of operation 0 of job 'A'");
    }

    @Test
    void cuttingAndPackingNameWhatCannotBePlaced() {
        assertThat(reasons(Optimize.cuttingStock()
                .stock("R", s -> s.length(50))
                .required("P", p -> p.length(80))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve()))
                .containsExactly("all 1 piece(s) 'P' must be cut");
        assertThat(reasons(Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("P", p -> p.length(20).width(5))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve()))
                .containsExactly("piece 'P#1' must be cut");
        assertThat(reasons(Optimize.cuttingStock()
                .stock("S", s -> s.length(10).width(10))
                .required("P", p -> p.length(20).width(5))
                .guillotine(true)
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve()))
                .containsExactly("piece 'P#1' must be cut");

        List<String> packing = reasons(Optimize.packing()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(5))
                .box("A", b -> b.length(2).width(2).height(2).weight(3))
                .box("B", b -> b.length(2).width(2).height(2).weight(3))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve());
        assertThat(packing).containsExactlyInAnyOrder("weight limit 5 of container 'C'",
                "box 'A' must be packed", "box 'B' must be packed");
    }

    @Test
    void feasibleModelsAreSolvedOnce() {
        var sol = Optimize.jobShop().machines("M")
                .job("J", j -> j.operation().on("M").takes(3))
                .solver().timeLimit(LIMIT).explainInfeasibility(true).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.diagnostics().infeasibilityReasons()).isEmpty();
    }
}

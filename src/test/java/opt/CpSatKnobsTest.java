package opt;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link CpSatSolver} contract (warm start, intermediate-solution callbacks, cancel,
 * seed reproducibility, fresh solvers, null guards) exercised on every CP-SAT domain, not
 * just the job shop. Each domain supplies one small instance with a provable optimum.
 */
class CpSatKnobsTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    private static <S extends Solution> void checkKnobs(CpSatProblem<S> problem) {
        // Baseline solve is optimal.
        S first = problem.solver().timeLimit(LIMIT).workers(4).solve();
        assertThat(first.isOptimal()).as("baseline must be provably optimal").isTrue();
        assertThat(first.objective()).isPresent();

        // Warm start from the previous result reaches the same optimum.
        S warmed = problem.solver().timeLimit(LIMIT).workers(4).warmStart(first).solve();
        assertThat(warmed.isOptimal()).isTrue();
        assertThat(warmed.objective()).isEqualTo(first.objective());

        // Intermediate solutions arrive, are FEASIBLE, and never beat the final optimum.
        List<S> seen = new ArrayList<>();
        S withListener = problem.solver().timeLimit(LIMIT).workers(1).onSolution(seen::add).solve();
        assertThat(withListener.isOptimal()).isTrue();
        assertThat(seen).isNotEmpty();
        for (S s : seen) {
            assertThat(s.status()).isEqualTo(SolveStatus.FEASIBLE);
            assertThat(s.objective()).isPresent();
            assertThat(s.runtime().isNegative()).isFalse();
        }

        // Same seed, single worker => identical objective.
        S a = problem.solver().timeLimit(LIMIT).workers(1).seed(11).solve();
        S b = problem.solver().timeLimit(LIMIT).workers(1).seed(11).solve();
        assertThat(a.objective()).isEqualTo(b.objective());

        // Fresh solver per call; cancel on an idle solver is a no-op; null guards.
        CpSatSolver<S> s1 = problem.solver();
        CpSatSolver<S> s2 = problem.solver();
        assertThat(s1).isNotSameAs(s2);
        s1.cancel();
        assertThat(s1.timeLimit(LIMIT).solve().isFeasible()).isTrue();
        assertThatThrownBy(() -> problem.solver().timeLimit(null)).isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
        assertThatThrownBy(() -> problem.solver().onSolution(null)).isInstanceOf(ModelException.class).hasMessageContaining("onSolution");
        assertThatThrownBy(() -> problem.solver().warmStart(null)).isInstanceOf(ModelException.class).hasMessageContaining("warmStart");
        assertThatThrownBy(() -> problem.solver().workers(0)).isInstanceOf(ModelException.class).hasMessageContaining("workers");
        assertThat(problem.warnings()).isEmpty();
    }

    @Test
    void jobShopKnobs() {
        checkKnobs(Optimize.jobShop().machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().onAnyOf("M3", "M1").takes(4).then().on("M2").takes(3))
                .build());
    }

    @Test
    void schedulingKnobs() {
        checkKnobs(Optimize.scheduling()
                .horizon(LocalDate.of(2026, 7, 6), 7)
                .employee("a", e -> e.skills("RN").costPerHour(10).prefersShift("day"))
                .employee("b", e -> e.skills("RN").costPerHour(12))
                .employee("c", e -> e.skills("RN").costPerHour(15))
                .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1).withSkill("RN"))
                .shift("night", s -> s.every(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                        .from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1).withSkill("RN"))
                .constrain(opt.scheduling.Rules.oneShiftPerEmployeePerDay())
                .constrain(opt.scheduling.Rules.maxShiftsPerWeek(5))
                .prefer(opt.scheduling.Goals.honorEmployeePreferences(50))
                .goal(opt.scheduling.Goals.totalLaborCost())
                .build());
    }

    @Test
    void knapsackKnobs() {
        checkKnobs(Optimize.multipleKnapsack()
                .bin("B1", b -> b.capacity(10)).bin("B2", b -> b.capacity(8))
                .item("I1", i -> i.profit(10).weight(6)).item("I2", i -> i.profit(8).weight(5))
                .item("I3", i -> i.profit(6).weight(5)).item("I4", i -> i.profit(7).weight(4))
                .item("I5", i -> i.profit(3).weight(3))
                .goal(opt.knapsack.Goals.maximizeProfit())
                .build());
    }

    @Test
    void packingKnobs() {
        checkKnobs(Optimize.packing()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .container("C2", c -> c.length(6).width(6).height(6).maxWeight(100))
                .box("A", b -> b.length(5).width(5).height(5).weight(1))
                .box("B", b -> b.length(5).width(5).height(5).weight(1))
                .box("R", b -> b.length(8).width(2).height(2).weight(1).rotatable(true))
                .goal(opt.packing.Goals.minimizeContainersUsed())
                .build());
    }

    @Test
    void bookingKnobs() {
        LocalDate day = LocalDate.of(2026, 7, 6);
        checkKnobs(Optimize.booking().horizon(day, 1)
                .resource("R1", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
                .resource("R2", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .appointment("A", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("R1", "R2"))
                .appointment("B", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("R1", "R2"))
                .appointment("C", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("R1").penaltyIfUnscheduled(5))
                .appointment("D", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("R1").penaltyIfUnscheduled(7))
                .goal(opt.booking.Goals.minimizeUnscheduledPenalty())
                .build());
    }

    @Test
    void cutting1DKnobs() {
        checkKnobs(Optimize.cuttingStock()
                .stock("R", s -> s.length(100).quantity(3).cost(10))
                .required("A", p -> p.length(60).quantity(2))
                .required("B", p -> p.length(40).quantity(3))
                .goal(opt.cutting.Goals.minimizeStockUsed())
                .build());
    }

    @Test
    void cutting2DKnobs() {
        checkKnobs(Optimize.cuttingStock()
                .stock("S", s -> s.length(20).width(10).quantity(2).cost(10))
                .required("A", p -> p.length(10).width(5).quantity(3))
                .required("B", p -> p.length(20).width(5).quantity(1))
                .goal(opt.cutting.Goals.minimizeStockUsed())
                .build());
    }

    @Test
    void warmStartIgnoresIdsThatNoLongerExist() {
        var small = Optimize.jobShop().machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2))
                .job("Gone", j -> j.operation().on("M2").takes(5))
                .build();
        var previous = small.solver().timeLimit(LIMIT).solve();
        // A re-planned model: "Gone" was removed, "New" was added, J1 grew an operation.
        var edited = Optimize.jobShop().machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M1").takes(1))
                .job("New", j -> j.operation().onAnyOf("M1", "M2").takes(4))
                .build();
        var result = edited.solver().timeLimit(LIMIT).warmStart(previous).solve();
        assertThat(result.isOptimal()).isTrue();
    }

    @Test
    void allBuildersReportNoWarningsByDefault() {
        assertThat(Optimize.scheduling().warnings()).isEmpty();
        assertThat(Optimize.vehicleRouting().warnings()).isEmpty();
        assertThat(Optimize.multipleKnapsack().warnings()).isEmpty();
        assertThat(Optimize.packing().warnings()).isEmpty();
        assertThat(Optimize.booking().warnings()).isEmpty();
        assertThat(Optimize.cuttingStock().warnings()).isEmpty();
    }

    @Test
    void goalNullIsRejectedEverywhere() {
        assertThatThrownBy(() -> Optimize.scheduling().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.vehicleRouting().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.multipleKnapsack().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.packing().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.booking().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.cuttingStock().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
        assertThatThrownBy(() -> Optimize.jobShop().goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal()");
    }
}

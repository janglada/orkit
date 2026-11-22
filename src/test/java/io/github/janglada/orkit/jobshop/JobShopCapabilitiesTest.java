package io.github.janglada.orkit.jobshop;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Precedences across jobs, machines that run several operations at once, and calendar time. */
class JobShopCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final LocalDateTime ORIGIN = LocalDateTime.of(2026, 7, 6, 8, 0);

    private static ScheduledOperation op(JobShopSchedule s, String job, int index) {
        return s.operationsOf(job).get(index);
    }

    // ── Cross-job precedence ────────────────────────────────────────────────

    @Test
    void aJobWaitsForTheJobsItDependsOn() {
        JobShopSchedule s = Optimize.jobShop().machines("M1", "M2")
                .job("Frame", j -> j.operation().on("M1").takes(5).then().on("M2").takes(3))
                .job("Paint", j -> j.operation().on("M2").takes(4))
                .job("Label", j -> j.operation().on("M1").takes(2))
                .precedence("Frame", "Paint")
                .precedence("Paint", 0, "Label", 0)
                .solver().timeLimit(LIMIT).solve();
        assertThat(s.isOptimal()).isTrue();
        assertThat(op(s, "Paint", 0).start()).isGreaterThanOrEqualTo(op(s, "Frame", 1).end());
        assertThat(op(s, "Label", 0).start()).isGreaterThanOrEqualTo(op(s, "Paint", 0).end());
        assertThat(s.makespan()).isEqualTo(5 + 3 + 4 + 2);
    }

    @Test
    void precedencesAreValidated() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("A", j -> j.operation().on("M").takes(1))
                .precedence("A", "Ghost").build())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown job id 'Ghost'");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("A", j -> j.operation().on("M").takes(1))
                .job("B", j -> j.operation().on("M").takes(1))
                .precedence("A", 3, "B", 0).build())
                .isInstanceOf(ModelException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("A", j -> j.operation().on("M").takes(1))
                .job("B", j -> j.operation().on("M").takes(1))
                .precedence("A", "B").precedence("B", "A").build())
                .isInstanceOf(ModelException.class).hasMessageContaining("cycle");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("A", j -> j.operation().on("M").takes(1).then().on("M").takes(1))
                .precedence("A", 1, "A", 0).build())
                .as("a precedence against the job's own order is a cycle")
                .isInstanceOf(ModelException.class).hasMessageContaining("cycle");
        assertThatThrownBy(() -> Optimize.jobShop().precedence("A", -1, "B", 0))
                .isInstanceOf(ModelException.class).hasMessageContaining("operation index");
    }

    // ── Machine capacity ────────────────────────────────────────────────────

    @Test
    void aMachineRunsUpToItsCapacityAtOnce() {
        JobShopBuilder b = Optimize.jobShop().machine("Oven", 2).machines("Pack");
        for (int i = 0; i < 3; i++) {
            b.job("Bake" + i, j -> j.operation().on("Oven").takes(10).then().on("Pack").takes(1));
        }
        JobShopSchedule s = b.solver().timeLimit(LIMIT).solve();
        assertThat(s.isOptimal()).isTrue();
        assertThat(s.makespan()).isEqualTo(21);
        List<ScheduledOperation> oven = s.timelineOf("Oven");
        for (long t = 0; t < s.makespan(); t++) {
            long at = t;
            long running = oven.stream().filter(o -> o.start() <= at && at < o.end()).count();
            assertThat(running).as("ovens busy at %d", t).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void capacityWorksWithFlexibleOperationsAndDowntime() {
        JobShopSchedule s = Optimize.jobShop().machine("Big", 2).machines("Small")
                .downtime("Big", 0, 5)
                .job("A", j -> j.operation().onAnyOf("Big", "Small").takes(5))
                .job("B", j -> j.operation().onAnyOf("Big", "Small").takes(5))
                .job("C", j -> j.operation().onAnyOf("Big", "Small").takes(5))
                .solver().timeLimit(LIMIT).solve();
        assertThat(s.isOptimal()).isTrue();
        assertThat(s.makespan()).isEqualTo(10);
        assertThat(s.timelineOf("Big")).allSatisfy(o -> assertThat(o.start()).isGreaterThanOrEqualTo(5));
    }

    @Test
    void setupsNeedAUnitCapacityMachine() {
        assertThatThrownBy(() -> Optimize.jobShop().machine("Oven", 2)
                .setupTimeOn("Oven", "A", "B", 3)
                .job("J", j -> j.operation().on("Oven").type("A").takes(1)).build())
                .isInstanceOf(ModelException.class).hasMessageContaining("capacity 1");
        assertThatThrownBy(() -> Optimize.jobShop().machine("Oven", 2)
                .defaultSetupTime(4)
                .job("J1", j -> j.operation().on("Oven").type("A").takes(1))
                .job("J2", j -> j.operation().on("Oven").type("B").takes(1))
                .solver().timeLimit(LIMIT).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("capacity 1");
        assertThatThrownBy(() -> Optimize.jobShop().machine("Oven", 0))
                .isInstanceOf(ModelException.class).hasMessageContaining("machine capacity");
    }

    // ── Calendar time ───────────────────────────────────────────────────────

    @Test
    void calendarInputsAndOutputs() {
        JobShopSchedule s = Optimize.jobShop()
                .startingAt(ORIGIN).timeUnit(Duration.ofMinutes(15))
                .machines("Lathe")
                .downtime("Lathe", ORIGIN, ORIGIN.plusMinutes(30))
                .job("J", j -> j.operation().on("Lathe").takes(Duration.ofHours(1))
                        .releaseAt(ORIGIN.plusMinutes(45))
                        .deadlineAt(ORIGIN.plusHours(3))
                        .dueBy(ORIGIN.plusHours(1)))
                .goal(Goals.minimizeTardiness(1))
                .solver().timeLimit(LIMIT).solve();
        assertThat(s.isOptimal()).isTrue();
        ScheduledOperation o = op(s, "J", 0);
        assertThat(o.start()).isEqualTo(3);
        assertThat(o.end()).isEqualTo(7);
        assertThat(o.startTime()).isEqualTo(ORIGIN.plusMinutes(45));
        assertThat(o.endTime()).isEqualTo(ORIGIN.plusMinutes(105));
        assertThat(s.makespanDuration()).contains(Duration.ofMinutes(105));
        assertThat(s.tardinessOf("J")).as("3 units of 15 minutes late").isEqualTo(3);
    }

    @Test
    void withoutACalendarTimesAreAbsent() {
        JobShopSchedule s = Optimize.jobShop().machines("M")
                .job("J", j -> j.operation().on("M").takes(2))
                .solver().timeLimit(LIMIT).solve();
        assertThat(op(s, "J", 0).startTime()).isNull();
        assertThat(s.makespanDuration()).isEmpty();
        // One-minute units by default.
        JobShopSchedule minutes = Optimize.jobShop().startingAt(ORIGIN).machines("M")
                .job("J", j -> j.operation().on("M").takes(Duration.ofMinutes(7)))
                .solver().timeLimit(LIMIT).solve();
        assertThat(op(minutes, "J", 0).endTime()).isEqualTo(ORIGIN.plusMinutes(7));
    }

    @Test
    void calendarArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("J", j -> j.operation().on("M").takes(Duration.ofMinutes(5))))
                .isInstanceOf(ModelException.class).hasMessageContaining("startingAt");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(ORIGIN).timeUnit(Duration.ofMinutes(15)).machines("M")
                .job("J", j -> j.operation().on("M").takes(Duration.ofMinutes(20))))
                .isInstanceOf(ModelException.class).hasMessageContaining("whole number");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(ORIGIN).machines("M")
                .job("J", j -> j.operation().on("M").takes(1).releaseAt(ORIGIN.minusHours(1))))
                .isInstanceOf(ModelException.class).hasMessageContaining("before the schedule origin");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(ORIGIN).machines("M")
                .job("J", j -> j.operation().on("M").takes(1).dueBy(null)))
                .isInstanceOf(ModelException.class).hasMessageContaining("must not be null");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(ORIGIN).machines("M")
                .job("J", j -> j.operation().on("M").takes((Duration) null)))
                .isInstanceOf(ModelException.class).hasMessageContaining("must not be null");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(ORIGIN).machines("M")
                .job("J", j -> j.operation().on("M").takes(Duration.ofDays(5000 * 365L))))
                .isInstanceOf(ModelException.class).hasMessageContaining("too many");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M")
                .job("J", j -> j.operation().on("M").takes(1)).startingAt(ORIGIN))
                .isInstanceOf(ModelException.class).hasMessageContaining("before any job");
        assertThatThrownBy(() -> Optimize.jobShop().timeUnit(Duration.ofMinutes(5)))
                .isInstanceOf(ModelException.class).hasMessageContaining("startingAt");
        assertThatThrownBy(() -> Optimize.jobShop().startingAt(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("startingAt");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M").downtime("M", ORIGIN, ORIGIN.plusHours(1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("calendar");
        // The unit may change before jobs, keeping the origin.
        JobShopSchedule s = Optimize.jobShop().startingAt(ORIGIN).timeUnit(Duration.ofMinutes(5))
                .startingAt(ORIGIN.plusHours(1)).machines("M")
                .job("J", j -> j.operation().on("M").takes(Duration.ofMinutes(10))
                        .deadlineAt(ORIGIN.plusHours(2)))
                .solver().timeLimit(LIMIT).solve();
        assertThat(s.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(op(s, "J", 0).end()).isEqualTo(2);
    }
}

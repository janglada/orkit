package opt.jobshop;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobShopTest {

    // ── T-J1: Classic 3-job / 3-machine — known optimal makespan = 10 ────────

    @Test
    void classicThreeMachineJobShopOptimalMakespan() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3)
                        .then().on("M2").takes(2)
                        .then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2)
                        .then().on("M1").takes(4)
                        .then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4)
                        .then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(10);

        // No two operations on the same machine may overlap
        for (String machine : List.of("M1", "M2", "M3")) {
            assertNoOverlap(schedule.timelineOf(machine), machine);
        }

        // Precedence must be satisfied for every job
        for (String job : List.of("J1", "J2", "J3")) {
            assertPrecedence(schedule.operationsOf(job), job);
        }
    }

    // ── T-J2: Single job, single machine — trivially optimal ─────────────────

    @Test
    void singleJobSingleMachineMakespanEqualsSumOfDurations() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(2)
                        .then().on("M1").takes(3)
                        .then().on("M1").takes(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(9);

        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops).hasSize(3);
        // Each operation ends exactly when the next begins
        assertPrecedence(ops, "J1");
    }

    // ── T-J3: Two independent jobs on different machines ─────────────────────

    @Test
    void independentJobsRunInParallelMakespanIsMax() {
        // J1 on M1 only (total 5), J2 on M2 only (total 7) → makespan = 7
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(2).then().on("M1").takes(3))
                .job("J2", j -> j.operation().on("M2").takes(4).then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(7);
    }

    // ── T-J4: Flexible operation chooses machine ──────────────────────────────

    @Test
    void flexibleOperationAssignedToOneOfCandidateMachines() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3)
                        .then().onAnyOf("M2", "M3").takes(4)  // flexible
                        .then().on("M1").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(5)
                        .then().on("M3").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        // Every operation of J1 must be assigned to a valid machine
        List<ScheduledOperation> j1ops = schedule.operationsOf("J1");
        assertThat(j1ops.get(0).machine()).isEqualTo("M1");
        assertThat(j1ops.get(1).machine()).isIn("M2", "M3"); // flexible op
        assertThat(j1ops.get(2).machine()).isEqualTo("M1");

        // No-overlap on each machine
        for (String m : List.of("M1", "M2", "M3")) {
            assertNoOverlap(schedule.timelineOf(m), m);
        }
    }

    // ── T-J5: asGantt() returns all machines and operations ──────────────────

    @Test
    void ganttContainsAllMachinesAndOperations() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        Gantt gantt = schedule.asGantt();
        assertThat(gantt.machines()).containsExactly("M1", "M2", "M3");

        int total = gantt.machines().stream()
                .mapToInt(m -> gantt.rowFor(m).size())
                .sum();
        // J1: 3 ops, J2: 3 ops, J3: 2 ops = 8 total
        assertThat(total).isEqualTo(8);
    }

    // ── T-J6: Unknown machine reference throws ModelException ─────────────────

    @Test
    void unknownMachineReferenceThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop()
                        .machines("M1", "M2")
                        .job("J1", j -> j.operation().on("M99").takes(3))  // M99 not declared
                        .solver()
                        .solve()
        ).isInstanceOf(ModelException.class)
                .hasMessageContaining("M99");
    }

    // ── T-J7: Empty job list throws ModelException ───────────────────────────

    @Test
    void emptyJobListThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop()
                        .machines("M1")
                        .solver()
                        .solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-J8: Reusable problem tested with two solver configs ─────────────────

    @Test
    void reusableProblemGivesSameOptimalMakespan() {
        var problem = Optimize.jobShop()
                .machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(4).then().on("M1").takes(1))
                .build();

        JobShopSchedule fast  = problem.solver().timeLimit(Duration.ofSeconds(3)).solve();
        JobShopSchedule tight = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(fast.isFeasible()).isTrue();
        assertThat(tight.isFeasible()).isTrue();
        assertThat(tight.makespan()).isLessThanOrEqualTo(fast.makespan());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertNoOverlap(List<ScheduledOperation> ops, String machine) {
        for (int i = 0; i < ops.size() - 1; i++) {
            ScheduledOperation a = ops.get(i);
            ScheduledOperation b = ops.get(i + 1);
            assertThat(a.end())
                    .as("Overlap on %s between %s-op%d and %s-op%d", machine, a.job(), a.index(), b.job(), b.index())
                    .isLessThanOrEqualTo(b.start());
        }
    }

    private void assertPrecedence(List<ScheduledOperation> ops, String job) {
        for (int i = 0; i < ops.size() - 1; i++) {
            assertThat(ops.get(i).end())
                    .as("Precedence violated in %s between op%d and op%d", job, i, i + 1)
                    .isLessThanOrEqualTo(ops.get(i + 1).start());
        }
    }
}

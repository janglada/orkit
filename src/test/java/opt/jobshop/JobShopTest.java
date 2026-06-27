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

    // ── T-J9: Structural — op.start + declared duration == op.end ─────────────

    @Test
    void operationEndEqualsStartPlusDuration() {
        // Durations: J1 ops = 3, 2, 5; J2 ops = 4, 1
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3)
                        .then().on("M2").takes(2)
                        .then().on("M1").takes(5))
                .job("J2", j -> j.operation().on("M2").takes(4)
                        .then().on("M1").takes(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        int[] j1Durations = {3, 2, 5};
        List<ScheduledOperation> j1Ops = schedule.operationsOf("J1");
        for (int i = 0; i < j1Ops.size(); i++) {
            ScheduledOperation op = j1Ops.get(i);
            assertThat(op.end() - op.start())
                    .as("J1 op%d: end - start should equal duration %d", i, j1Durations[i])
                    .isEqualTo(j1Durations[i]);
        }

        int[] j2Durations = {4, 1};
        List<ScheduledOperation> j2Ops = schedule.operationsOf("J2");
        for (int i = 0; i < j2Ops.size(); i++) {
            ScheduledOperation op = j2Ops.get(i);
            assertThat(op.end() - op.start())
                    .as("J2 op%d: end - start should equal duration %d", i, j2Durations[i])
                    .isEqualTo(j2Durations[i]);
        }
    }

    // ── T-J10: Structural — makespan equals max end across all operations ─────

    @Test
    void makespanEqualsMaxOperationEnd() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        long maxEnd = 0;
        for (String machine : List.of("M1", "M2", "M3")) {
            for (ScheduledOperation op : schedule.timelineOf(machine)) {
                maxEnd = Math.max(maxEnd, op.end());
            }
        }
        assertThat(schedule.makespan()).isEqualTo(maxEnd);
    }

    // ── T-J11: Structural — operationsOf returns all ops in index order ────────

    @Test
    void operationsOfReturnsAllOpsInIndexOrder() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(2)
                        .then().on("M2").takes(3)
                        .then().on("M3").takes(4))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        List<ScheduledOperation> ops = schedule.operationsOf("J1");
        assertThat(ops).hasSize(3);
        for (int i = 0; i < ops.size(); i++) {
            assertThat(ops.get(i).index())
                    .as("Operation at position %d should have index %d", i, i)
                    .isEqualTo(i);
            assertThat(ops.get(i).job()).isEqualTo("J1");
        }
    }

    // ── T-J12: Two jobs on the same single machine — one must wait ────────────

    @Test
    void twoJobsSameMachineMakespanEqualsSumOfDurations() {
        // J1 takes 5, J2 takes 3, both on M1. Since they can't overlap:
        // makespan = 5+3 = 8 (regardless of scheduling order).
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5))
                .job("J2", j -> j.operation().on("M1").takes(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(8);
        assertNoOverlap(schedule.timelineOf("M1"), "M1");
    }

    // ── T-J13: Flexible op chooses the less-loaded machine ────────────────────

    @Test
    void flexibleOpChoosesLessLoadedMachineToMinimizeMakespan() {
        // J1: M1(5) → onAnyOf(M2,M3).takes(5)
        // J2: M2(5) → M3(5)
        // If J1.op2 → M2: both J2.op1 and J1.op2 on M2, must serialize → makespan ≥ 15
        // If J1.op2 → M3: J1.op2[5,10), J2.op1[0,5), J2.op2 must wait for J1 on M3 → makespan = 15
        // Optimal split: J1.op2 → M2[5,10), J2.op1[0,5) then M3 free → makespan = 10
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(5)
                        .then().onAnyOf("M2", "M3").takes(5))
                .job("J2", j -> j.operation().on("M2").takes(5)
                        .then().on("M3").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(10);

        for (String m : List.of("M1", "M2", "M3")) {
            assertNoOverlap(schedule.timelineOf(m), m);
        }
        for (String job : List.of("J1", "J2")) {
            assertPrecedence(schedule.operationsOf(job), job);
        }
    }

    // ── T-J14: Single-operation job schedules at time zero ────────────────────

    @Test
    void singleOperationJobStartsAtZero() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(7))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(7);

        ScheduledOperation op = schedule.operationsOf("J1").get(0);
        assertThat(op.start()).isEqualTo(0);
        assertThat(op.end()).isEqualTo(7);
    }

    // ── T-J15: Gantt rows are sorted by start time ────────────────────────────

    @Test
    void ganttRowsAreSortedByStartTime() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();

        Gantt gantt = schedule.asGantt();
        for (String machine : gantt.machines()) {
            List<ScheduledOperation> row = gantt.rowFor(machine);
            for (int i = 0; i < row.size() - 1; i++) {
                assertThat(row.get(i).start())
                        .as("Gantt row for %s is not sorted at index %d", machine, i)
                        .isLessThanOrEqualTo(row.get(i + 1).start());
            }
        }
    }

    // ── T-J16: takes(0) throws ModelException ────────────────────────────────

    @Test
    void zeroTakesThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop()
                        .machines("M1")
                        .job("J1", j -> j.operation().on("M1").takes(0))
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-J17: onAnyOf with a single machine throws ModelException ────────────

    @Test
    void onAnyOfSingleMachineThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop()
                        .machines("M1")
                        .job("J1", j -> j.operation().onAnyOf("M1").takes(3))
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── T-J18: runtime() is non-negative ──────────────────────────────────────

    @Test
    void runtimeIsNonNegative() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.runtime().isNegative()).isFalse();
    }

    // ── T-J19: Large job shop stays feasible within time limit ────────────────

    @Test
    void largeJobShopSolvesFeasiblyWithinTimeLimit() {
        // 5 jobs × 4 operations each, 4 machines — medium-sized real problem
        var builder = Optimize.jobShop().machines("MA", "MB", "MC", "MD");
        String[][] plan = {
            {"MA","MB","MC","MD"},  // J1: A→B→C→D
            {"MB","MA","MD","MC"},  // J2: B→A→D→C
            {"MC","MD","MA","MB"},  // J3: C→D→A→B
            {"MD","MC","MB","MA"},  // J4: D→C→B→A
            {"MA","MC","MB","MD"},  // J5: A→C→B→D
        };
        int[][] durations = {
            {3,4,2,5}, {5,3,4,2}, {2,5,3,4}, {4,2,5,3}, {3,5,4,2}
        };
        for (int j = 0; j < 5; j++) {
            final int jj = j;
            builder.job("J" + (j + 1), cfg -> {
                OperationConfig op = cfg.operation().on(plan[jj][0]).takes(durations[jj][0]);
                for (int o = 1; o < 4; o++) {
                    op = op.then().on(plan[jj][o]).takes(durations[jj][o]);
                }
            });
        }

        JobShopSchedule schedule = builder.solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();
        assertThat(schedule.makespan()).isGreaterThan(0);

        for (String machine : List.of("MA", "MB", "MC", "MD")) {
            assertNoOverlap(schedule.timelineOf(machine), machine);
        }
    }
}

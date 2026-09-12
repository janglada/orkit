package opt.jobshop;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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

    // ── T-J20: Basic sequence-dependent setup, asymmetric pair ────────────────
    // Two jobs on one machine: J1 type ALU (dur 4), J2 type STEEL (dur 5).
    // setup ALU->STEEL = 12, STEEL->ALU = 8. Optimal order: STEEL first (gap 8)
    // => makespan = 5 + 8 + 4 = 17.

    @Test
    void setupTimeAppliedBetweenAdjacentTypedOps() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .setupTime("ALU", "STEEL", 12)
                .setupTime("STEEL", "ALU", 8)
                .job("J1", j -> j.operation().on("M1").type("ALU").takes(4))
                .job("J2", j -> j.operation().on("M1").type("STEEL").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(17);

        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops).hasSize(2);
        // Cheaper direction STEEL -> ALU is chosen: STEEL op first, gap = 8.
        assertThat(ops.get(0).job()).isEqualTo("J2");
        assertThat(ops.get(1).job()).isEqualTo("J1");
        long gap = ops.get(1).start() - ops.get(0).end();
        assertThat(gap).as("changeover gap should be STEEL->ALU = 8").isEqualTo(8);
    }

    // ── T-J21: defaultSetupTime applies to unlisted typed pairs ───────────────

    @Test
    void defaultSetupTimeAppliedToUnlistedTypedPair() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .defaultSetupTime(5)
                .job("J1", j -> j.operation().on("M1").type("A").takes(2))
                .job("J2", j -> j.operation().on("M1").type("B").takes(2))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(2 + 5 + 2);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(1).start() - ops.get(0).end()).isEqualTo(5);
    }

    // ── T-J22: untyped operations never pay setup (default not applied) ──────

    @Test
    void untypedOperationsPayNoSetup() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .defaultSetupTime(5)
                .job("J1", j -> j.operation().on("M1").type("A").takes(2))
                .job("J2", j -> j.operation().on("M1").takes(2)) // untyped
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(2 + 0 + 2);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(1).start() - ops.get(0).end()).isEqualTo(0);
    }

    // ── T-J23: same-type transitions are zero unless an explicit (T,T) entry ──

    @Test
    void sameTypeTransitionIsZeroUnlessExplicit() {
        // (a) No explicit (A,A) entry: same-type ops have zero gap even with a default.
        JobShopSchedule noEntry = Optimize.jobShop()
                .machines("M1")
                .defaultSetupTime(5)
                .job("J1", j -> j.operation().on("M1").type("A").takes(3))
                .job("J2", j -> j.operation().on("M1").type("A").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(noEntry.makespan()).isEqualTo(6);
        assertThat(noEntry.timelineOf("M1").get(1).start()
                - noEntry.timelineOf("M1").get(0).end()).isEqualTo(0);

        // (b) Explicit (A,A) entry applies.
        JobShopSchedule withEntry = Optimize.jobShop()
                .machines("M1")
                .setupTime("A", "A", 4)
                .job("J1", j -> j.operation().on("M1").type("A").takes(3))
                .job("J2", j -> j.operation().on("M1").type("A").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(withEntry.makespan()).isEqualTo(3 + 4 + 3);
        assertThat(withEntry.timelineOf("M1").get(1).start()
                - withEntry.timelineOf("M1").get(0).end()).isEqualTo(4);
    }

    // ── T-J24: initialTypeOn makes the first operation pay a setup ────────────

    @Test
    void initialTypeAppliesSetupToFirstOperation() {
        // With an initial type B != A, the first (and only) op pays setup B->A = 8.
        JobShopSchedule withInitial = Optimize.jobShop()
                .machines("M1")
                .setupTime("B", "A", 8)
                .initialTypeOn("M1", "B")
                .job("J1", j -> j.operation().on("M1").type("A").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(withInitial.makespan()).isEqualTo(8 + 5);
        assertThat(withInitial.operationsOf("J1").get(0).start()).isEqualTo(8);

        // Without an initial type, the first op starts at 0.
        JobShopSchedule noInitial = Optimize.jobShop()
                .machines("M1")
                .setupTime("B", "A", 8)
                .job("J1", j -> j.operation().on("M1").type("A").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(noInitial.makespan()).isEqualTo(5);
        assertThat(noInitial.operationsOf("J1").get(0).start()).isEqualTo(0);
    }

    // ── T-J25: machine-specific setup overrides the global matrix ────────────

    @Test
    void machineSpecificSetupOverridesGlobal() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("MILL", "LATHE")
                .setupTime("ALU", "STEEL", 12)              // global
                .setupTimeOn("MILL", "ALU", "STEEL", 20)    // MILL override
                .job("J1", j -> j.operation().on("MILL").type("ALU").takes(3)
                        .then().on("MILL").type("STEEL").takes(3))
                .job("J2", j -> j.operation().on("LATHE").type("ALU").takes(3)
                        .then().on("LATHE").type("STEEL").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        // MILL: 3 + 20 + 3 = 26 ; LATHE: 3 + 12 + 3 = 18 ; parallel => 26.
        assertThat(schedule.makespan()).isEqualTo(26);
        List<ScheduledOperation> mill = schedule.timelineOf("MILL");
        List<ScheduledOperation> lathe = schedule.timelineOf("LATHE");
        assertThat(mill.get(1).start() - mill.get(0).end()).isEqualTo(20);
        assertThat(lathe.get(1).start() - lathe.get(0).end()).isEqualTo(12);
    }

    // ── T-J26: flexible typed op pays setup on the chosen machine ────────────
    // MILL changeover is made expensive (machine-specific 100), LATHE stays cheap
    // (global 1). The flexible ALU op must land on LATHE to minimize makespan.

    @Test
    void flexibleTypedOpPaysSetupOnChosenMachine() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("MILL", "LATHE")
                .setupTime("ALU", "STEEL", 1)
                .setupTime("STEEL", "ALU", 1)
                .setupTimeOn("MILL", "ALU", "STEEL", 100)
                .setupTimeOn("MILL", "STEEL", "ALU", 100)
                .job("J1", j -> j.operation().onAnyOf("MILL", "LATHE").type("ALU").takes(5))
                .job("J2", j -> j.operation().on("MILL").type("STEEL").takes(5))
                .job("J3", j -> j.operation().on("LATHE").type("STEEL").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.isFeasible()).isTrue();
        // Optimal: J1 -> LATHE, gap 1 there => makespan 5 + 1 + 5 = 11.
        assertThat(schedule.makespan()).isEqualTo(11);
        ScheduledOperation j1 = schedule.operationsOf("J1").get(0);
        assertThat(j1.machine()).isEqualTo("LATHE");

        List<ScheduledOperation> lathe = schedule.timelineOf("LATHE");
        assertThat(lathe).hasSize(2);
        assertThat(lathe.get(1).start() - lathe.get(0).end()).isEqualTo(1);
        assertThat(schedule.timelineOf("MILL")).hasSize(1);
    }

    // ── T-J27: validation — negative setup, unknown machine, unused-type warn ─

    @Test
    void negativeSetupTimeThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop().machines("M1").setupTime("A", "B", -1)
        ).isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
    }

    @Test
    void setupTimeOnUnknownMachineThrowsModelException() {
        assertThatThrownBy(() ->
                Optimize.jobShop()
                        .machines("M1")
                        .setupTimeOn("NOPE", "A", "B", 5)
                        .job("J1", j -> j.operation().on("M1").takes(3))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("NOPE");
    }

    @Test
    void unusedMatrixTypeIsReportedAsWarning() {
        var problem = Optimize.jobShop()
                .machines("M1")
                .setupTime("FOO", "BAR", 5)            // neither used
                .job("J1", j -> j.operation().on("M1").type("ALU").takes(3))
                .build();
        assertThat(problem.warnings()).hasSize(2)
                .anySatisfy(w -> assertThat(w).contains("FOO"))
                .anySatisfy(w -> assertThat(w).contains("BAR"));
        assertThat(Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3)).build().warnings()).isEmpty();
    }

    // ── T-J28: backward-compat — types without a matrix behave as no setups ──

    @Test
    void typedOpsWithoutSetupMatrixBehaveAsNoSetups() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").type("A").takes(3))
                .job("J2", j -> j.operation().on("M1").type("B").takes(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        // No setup matrix => plain NoOverlap => back-to-back, makespan = 6, gap 0.
        assertThat(schedule.makespan()).isEqualTo(6);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(1).start() - ops.get(0).end()).isEqualTo(0);
    }

    // ── T-J29: Asymmetric matrix — solver picks the cheaper order ────────────
    // A->B = 10, B->A = 2, both ops otherwise symmetric (dur 5). Optimal order
    // is B first (gap 2) => makespan = 5 + 2 + 5 = 12 (vs 20 the other way).

    @Test
    void asymmetricMatrixPicksCheaperOrder() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .setupTime("A", "B", 10)
                .setupTime("B", "A", 2)
                .job("J1", j -> j.operation().on("M1").type("A").takes(5))
                .job("J2", j -> j.operation().on("M1").type("B").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(12);

        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(0).job()).isEqualTo("J2"); // B first
        assertThat(ops.get(1).job()).isEqualTo("J1");
        long gap = ops.get(1).start() - ops.get(0).end();
        assertThat(gap).as("gap should be the cheaper B->A = 2").isEqualTo(2);
    }

    // ── T-J30: Global entry wins over defaultSetupTime ───────────────────────
    // A->B has a global entry (3); default is 10. The optimal order is A first
    // (gap 3 = global, not 10 = default) => makespan = 2 + 3 + 2 = 7.

    @Test
    void globalEntryWinsOverDefault() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .defaultSetupTime(10)
                .setupTime("A", "B", 3)            // global, cheaper than default
                .job("J1", j -> j.operation().on("M1").type("A").takes(2))
                .job("J2", j -> j.operation().on("M1").type("B").takes(2))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(2 + 3 + 2);

        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(0).job()).isEqualTo("J1"); // A first to use the cheap global 3
        long gap = ops.get(1).start() - ops.get(0).end();
        assertThat(gap).as("gap should be global 3, not default 10").isEqualTo(3);
    }

    // ── T-J31: Flexible op with setups — lands on one machine, the other is bypassed
    // Two identical machines; the flexible A op must land on one of them. Whichever
    // it lands on pays the A<->B setup (gap 4); the other machine keeps only its
    // fixed B op and pays nothing.

    @Test
    void flexibleOpLandsOnOneMachineOtherIsBypassed() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .setupTime("A", "B", 4)
                .setupTime("B", "A", 4)
                .job("J1", j -> j.operation().onAnyOf("M1", "M2").type("A").takes(5))
                .job("J2", j -> j.operation().on("M1").type("B").takes(5))
                .job("J3", j -> j.operation().on("M2").type("B").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        // Either landing yields 5 + 4 + 5 = 14 on the chosen machine; the other = 5.
        assertThat(schedule.makespan()).isEqualTo(14);

        String chosen = schedule.operationsOf("J1").get(0).machine();
        String other  = chosen.equals("M1") ? "M2" : "M1";

        List<ScheduledOperation> chosenRow = schedule.timelineOf(chosen);
        List<ScheduledOperation> otherRow  = schedule.timelineOf(other);
        assertThat(chosenRow).hasSize(2);
        assertThat(otherRow).hasSize(1); // absent on the other machine => bypassed
        assertThat(chosenRow.get(1).start() - chosenRow.get(0).end()).isEqualTo(4);
        // The bypassed machine's lone op starts at 0 (no setup charged there).
        assertThat(otherRow.get(0).start()).isEqualTo(0);
    }

    // ── T-J32: Gap is >= setup even when precedence forces a larger gap ──────
    // J1.op0 runs on M2 (dur 20) then J1.op1 on M1 (type B); J2.op0 on M1 (type A).
    // J1.op1 cannot start before 20 (precedence from the M2 op). The M1 gap between
    // the A op and the B op is at least setup A->B = 3 (it may be larger when the
    // solver packs loosely, but never smaller). Makespan is pinned at 22 by J1.

    @Test
    void gapRespectsSetupLowerBoundWhenPrecedenceForcesLateStart() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .setupTime("A", "B", 3)
                .job("J1", j -> j.operation().on("M2").type("X").takes(20)
                        .then().on("M1").type("B").takes(2))
                .job("J2", j -> j.operation().on("M1").type("A").takes(2))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);

        List<ScheduledOperation> m1 = schedule.timelineOf("M1");
        assertThat(m1).hasSize(2);
        // J2.op0 (A) runs first, J1.op1 (B) second (the only optimal order).
        ScheduledOperation a = m1.get(0);
        ScheduledOperation b = m1.get(1);
        assertThat(a.job()).isEqualTo("J2");
        assertThat(b.job()).isEqualTo("J1");
        long gap = b.start() - a.end();
        assertThat(gap).as("gap must be >= setup A->B = 3").isGreaterThanOrEqualTo(3);
        // J1.op1.start >= 20 (precedence) and dur 2 => makespan pinned at 22;
        // the setup is respected without increasing the makespan further.
        assertThat(schedule.makespan()).isEqualTo(22);
    }

    // ── T-J33: Blank/null job id is rejected ─────────────────────────────────

    @Test
    void blankJobIdThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("", j -> j.operation().on("M1").takes(3))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("job id");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("   ", j -> j.operation().on("M1").takes(3))
                .solver().solve()).isInstanceOf(ModelException.class);
    }

    // ── T-J34: Duplicate job id is rejected ──────────────────────────────────

    @Test
    void duplicateJobIdThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .job("J1", j -> j.operation().on("M1").takes(2))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("Duplicate job id");
    }

    // ── T-J35: A job with no operations is rejected ──────────────────────────

    @Test
    void jobWithNoOperationsThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> {}) // never calls operation()
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("at least one operation");
    }

    // ── T-J36: Operation with no machine before .then() throws ───────────────

    @Test
    void operationWithNoMachineBeforeThenThrows() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().takes(3).then().on("M1").takes(2))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(".on(");
    }

    // ── T-J36b: operation() twice without then() is rejected, not silently dropped ──

    @Test
    void operationCalledTwiceThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1", "M2")
                .job("J1", j -> {
                    j.operation().on("M1").takes(5);
                    j.operation().on("M2").takes(3);
                })
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("then()");
    }

    // ── T-J37: Operation with no .takes() is rejected at commit ──────────────

    @Test
    void operationWithNoDurationThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1")) // no .takes()
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(".takes(");
    }

    // ── T-J38: Negative duration is rejected ────────────────────────────────

    @Test
    void negativeDurationThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(-1))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(">= 1");
    }

    // ── T-J39: onAnyOf with duplicate candidates is deduped then rejected ────

    @Test
    void onAnyOfDuplicateCandidatesThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1", "M2")
                .job("J1", j -> j.operation().onAnyOf("M1", "M1").takes(3))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("at least 2 distinct");
    }

    // ── T-J40: type(null/blank) coerces to untyped (no setup applied) ────────

    @Test
    void blankTypeCoercesToUntyped() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .defaultSetupTime(5) // would apply between typed ops, but all are untyped here
                .job("J1", j -> j.operation().on("M1").type(null).takes(2))
                .job("J2", j -> j.operation().on("M1").type("").takes(2))
                .job("J3", j -> j.operation().on("M1").type("   ").takes(2))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        // All untyped => no setup => back-to-back, makespan = 6.
        assertThat(schedule.makespan()).isEqualTo(6);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(1).start() - ops.get(0).end()).isEqualTo(0);
        assertThat(ops.get(2).start() - ops.get(1).end()).isEqualTo(0);
    }

    // ── T-J41: Unknown-machine/job accessors and empty Gantt rows ────────────

    @Test
    void unknownAccessorsReturnEmpty() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.timelineOf("NOPE")).isEmpty();
        assertThat(schedule.operationsOf("NOPE")).isEmpty();
        assertThat(schedule.asGantt().rowFor("M2")).isEmpty(); // M2 declared but unused
    }

    // ── T-J42: Setup-matrix validation edges ─────────────────────────────────

    @Test
    void negativeSetupTimesAreRejected() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").defaultSetupTime(-1))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").setupTimeOn("M1", "A", "B", -1))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
    }

    @Test
    void initialTypeOnUnknownMachineOrBlankTypeThrows() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .initialTypeOn("NOPE", "A")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("NOPE");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").initialTypeOn("M1", "  "))
                .isInstanceOf(ModelException.class).hasMessageContaining("non-blank");
    }

    // ── T-J43: A setup pair declared twice keeps the last value ──────────────

    @Test
    void setupPairDeclaredTwiceKeepsLastValue() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .setupTime("A", "B", 100)
                .setupTime("A", "B", 3)   // overwrites the 100 above
                .setupTime("B", "A", 100) // makes A-first the cheaper direction
                .job("J1", j -> j.operation().on("M1").type("A").takes(2))
                .job("J2", j -> j.operation().on("M1").type("B").takes(2))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        // A->B = 3 (overwritten) => A first, gap 3 => makespan 2 + 3 + 2 = 7.
        // (If the 100 had won, both directions would cost 100 => makespan 104.)
        assertThat(schedule.makespan()).isEqualTo(7);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(0).job()).isEqualTo("J1"); // A first
        assertThat(ops.get(1).start() - ops.get(0).end()).isEqualTo(3);
    }

    // ── T-J44: release date forces the operation to start at or after it ──────

    @Test
    void releaseDateLowerBoundsOperationStart() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5).releaseAt(10))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        // The earliest start is the release date; makespan = 10 + 5.
        ScheduledOperation op = schedule.operationsOf("J1").get(0);
        assertThat(op.start()).isEqualTo(10);
        assertThat(op.end()).isEqualTo(15);
        assertThat(schedule.makespan()).isEqualTo(15);
    }

    // ── T-J45: a deadline forces the operation to run first so it can finish in time

    @Test
    void deadlineForcesOrderToMeetDueTime() {
        // Two 5-unit ops on one machine: makespan 10 either order. J1 has a
        // deadline of 7, so it must run first (end 5 <= 7); running second
        // would end at 10 and violate the deadline.
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5).deadlineAt(7))
                .job("J2", j -> j.operation().on("M1").takes(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        List<ScheduledOperation> ops = schedule.timelineOf("M1");
        assertThat(ops.get(0).job()).isEqualTo("J1");          // J1 forced first
        assertThat(ops.get(0).end()).isLessThanOrEqualTo(7);   // deadline respected
        assertThat(ops.get(1).start()).isEqualTo(5);          // J2 immediately after
        assertThat(schedule.makespan()).isEqualTo(10);
    }

    // ── T-J46: a deadline tighter than the work is infeasible ────────────────

    @Test
    void deadlineTighterThanDurationIsInfeasible() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5).deadlineAt(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.INFEASIBLE);
    }

    // ── T-J47: soft due date past the optimum ⇒ zero tardiness, objective 0 ──

    @Test
    void softDueDatePastOptimumYieldsZeroTardiness() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .goal(Goals.minimizeTardiness(2))
                .job("J1", j -> j.operation().on("M1").takes(5).dueBy(100))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.isFeasible()).isTrue();
        // end = 5 ≤ due 100 ⇒ tardiness 0 ⇒ objective = 2 * 0 = 0.
        assertThat(schedule.objective().getAsDouble()).isCloseTo(0.0, within(1e-9));
    }

    // ── T-J48: soft due date tighter than the work ⇒ tardiness > 0, objective = weight*tardiness

    @Test
    void softDueDateTighterThanWorkIncurTardiness() {
        // Op takes 5, due at 3 ⇒ minimum tardiness = 5 - 3 = 2; objective = 2 * 2 = 4.
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .goal(Goals.minimizeTardiness(2))
                .job("J1", j -> j.operation().on("M1").takes(5).dueBy(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.operationsOf("J1").get(0).end()).isEqualTo(5);
        assertThat(schedule.objective().getAsDouble()).isCloseTo(4.0, within(1e-9));
    }

    // ── T-J49: combined makespan + tardiness objective ───────────────────────

    @Test
    void combinedMakespanAndTardinessObjective() {
        // makespan weight 1, tardiness weight 100. Op takes 5 due at 3 ⇒
        // objective = 1 * 5 (makespan) + 100 * 2 (tardiness) = 205.
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .goal(Goals.minimizeMakespanAndTardiness(1, 100))
                .job("J1", j -> j.operation().on("M1").takes(5).dueBy(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(5);
        assertThat(schedule.objective().getAsDouble()).isCloseTo(205.0, within(1e-6));
    }

    // ── T-J50: soft due dates are inert under the default MinimizeMakespan goal

    @Test
    void softDueDateInertUnderDefaultMakespanGoal() {
        // Same op as T-J48 but with the default makespan goal: the due date is
        // ignored, the schedule is identical to one without it.
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5).dueBy(3))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(schedule.makespan()).isEqualTo(5);
    }

    // ── T-J51: release + duration > deadline is rejected at commit ────────────

    @Test
    void releasePlusDurationExceedingDeadlineThrows() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(10).releaseAt(5).deadlineAt(10))
                .solver().solve()).isInstanceOf(ModelException.class)
                .hasMessageContaining("exceeds deadline");
    }

    // ── T-J52: negative release / deadline / due are rejected ─────────────────

    @Test
    void negativeReleaseDeadlineDueThrowModelException() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3).releaseAt(-1))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3).deadlineAt(-1))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3).dueBy(-1))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining(">= 0");
    }

    // ── T-J53: non-positive tardiness weights and null goal are rejected ──────

    @Test
    void nonPositiveWeightsAndNullGoalAreRejected() {
        assertThatThrownBy(() -> Goals.minimizeTardiness(0))
                .isInstanceOf(ModelException.class).hasMessageContaining("> 0");
        assertThatThrownBy(() -> Goals.minimizeTardiness(-1))
                .isInstanceOf(ModelException.class).hasMessageContaining("> 0");
        assertThatThrownBy(() -> Goals.minimizeMakespanAndTardiness(0, 1))
                .isInstanceOf(ModelException.class).hasMessageContaining("> 0");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").goal(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("null");
    }

    // ── T-J54: release/deadline window on a flexible op bounds the shared start/end

    @Test
    void flexibleOpRespectsReleaseDeadlineWindow() {
        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2")
                .job("J1", j -> j.operation().onAnyOf("M1", "M2").takes(5)
                        .releaseAt(10).deadlineAt(20))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(schedule.status()).isEqualTo(SolveStatus.OPTIMAL);
        ScheduledOperation op = schedule.operationsOf("J1").get(0);
        assertThat(op.machine()).isIn("M1", "M2");
        assertThat(op.start()).isGreaterThanOrEqualTo(10); // release
        assertThat(op.end()).isLessThanOrEqualTo(20);      // deadline
        assertThat(op.end() - op.start()).isEqualTo(5);
    }
}

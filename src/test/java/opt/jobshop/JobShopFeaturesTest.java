package opt.jobshop;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deeper coverage of downtime, weights, lags and tardiness, plus a schedule invariant
 * validator (machine exclusivity, precedence, lags, downtime, windows, setups) run on a
 * randomized flexible instance with every feature switched on.
 */
class JobShopFeaturesTest {

    private static ScheduledOperation op(JobShopSchedule s, String job, int index) {
        return s.operationsOf(job).get(index);
    }

    @Test
    void warmStartWithFlexibleOperationsKeepsMachineChoiceHints() {
        var problem = Optimize.jobShop().machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().onAnyOf("M1", "M2").takes(4).then().on("M3").takes(2))
                .job("J2", j -> j.operation().onAnyOf("M2", "M3").takes(3).then().onAnyOf("M1", "M3").takes(3))
                .job("J3", j -> j.operation().on("M1").takes(5))
                .build();
        var first = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(first.isOptimal()).isTrue();
        var again = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).warmStart(first).solve();
        assertThat(again.isOptimal()).isTrue();
        assertThat(again.makespan()).isEqualTo(first.makespan());
        assertThat(again.operations()).hasSize(5);
    }

    @Test
    void explicitMakespanGoalMatchesDefault() {
        var builder = Optimize.jobShop().machines("M1", "M2")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(4).then().on("M1").takes(1));
        var def = builder.solver().timeLimit(Duration.ofSeconds(5)).solve();
        var explicit = builder.goal(Goals.minimizeMakespan()).solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(explicit.makespan()).isEqualTo(def.makespan());
    }

    @Test
    void downtimeAppliesToFlexibleOperationOnChosenMachine() {
        JobShopSchedule s = Optimize.jobShop().machines("M1", "M2")
                .downtime("M1", 0, 100)                       // M1 unusable early
                .job("J1", j -> j.operation().onAnyOf("M1", "M2").takes(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(s.isOptimal()).isTrue();
        assertThat(op(s, "J1", 0).machine()).isEqualTo("M2");
        assertThat(s.makespan()).isEqualTo(5);
    }

    @Test
    void downtimeOnUnusedMachineAndMultipleWindowsAreHandled() {
        JobShopSchedule s = Optimize.jobShop().machines("M1", "IDLE")
                .downtime("IDLE", 0, 50).downtime("M1", 2, 4).downtime("M1", 6, 8)
                .job("J1", j -> j.operation().on("M1").takes(2))
                .job("J2", j -> j.operation().on("M1").takes(2))
                .job("J3", j -> j.operation().on("M1").takes(2))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(s.isOptimal()).isTrue();
        assertThat(s.makespan()).isEqualTo(10);              // slots [0,2) [4,6) [8,10)
        for (ScheduledOperation o : s.timelineOf("M1")) {
            assertThat(o.start() < 4 && o.end() > 2).isFalse();
            assertThat(o.start() < 8 && o.end() > 6).isFalse();
        }
    }

    @Test
    void noWaitCombinedWithDowntimeCanBeInfeasible() {
        // Before its deadline of 10, M2 is free only during [3,4): too short for a 2-unit op.
        // (Without the deadline the solver would legitimately schedule the job after the downtime.)
        JobShopSchedule s = Optimize.jobShop().machines("M1", "M2")
                .downtime("M2", 0, 3).downtime("M2", 4, 1000)
                .job("J1", j -> j.noWait().operation().on("M1").takes(2).then().on("M2").takes(2).deadlineAt(10))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(s.status()).isEqualTo(SolveStatus.INFEASIBLE);
        assertThat(s.operations()).isEmpty();
        assertThat(s.tardinessOf("J1")).isZero();
    }

    @Test
    void weightsApplyUnderCombinedMakespanAndTardinessGoal() {
        JobShopSchedule s = Optimize.jobShop().machines("M1")
                .goal(Goals.minimizeMakespanAndTardiness(1, 1))
                .job("light", j -> j.weight(1).operation().on("M1").takes(5).dueBy(5))
                .job("heavy", j -> j.weight(3).operation().on("M1").takes(5).dueBy(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(op(s, "heavy", 0).start()).isEqualTo(0);
        assertThat(s.objective()).hasValue(10.0 + 5.0);        // makespan 10 + light tardy 5 × weight 1
        assertThat(s.totalTardiness()).isEqualTo(5);
    }

    @Test
    void lagValidation() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").job("J", j -> j.maxLag(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("maxLag");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").job("J", j -> j.operation().on("M1").takes(1).maxLagAfterPrevious(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("maxLagAfterPrevious");
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1").downtime("M1", -1, 5))
                .isInstanceOf(ModelException.class).hasMessageContaining("downtime from");
    }

    @Test
    void randomizedFlexibleInstanceSatisfiesAllInvariants() {
        Random rnd = new Random(42);
        String[] machines = {"A", "B", "C", "D"};
        int setupAB = 2, lag = 4;
        Map<String, long[][]> downtimeOf = Map.of("A", new long[][]{{10, 15}, {40, 45}}, "C", new long[][]{{0, 5}});
        var builder = Optimize.jobShop().machines(machines)
                .setupTime("X", "Y", setupAB).setupTime("Y", "X", setupAB).defaultSetupTime(1)
                .goal(Goals.minimizeMakespanAndTardiness(1, 2));
        for (var e : downtimeOf.entrySet()) for (long[] w : e.getValue()) builder.downtime(e.getKey(), w[0], w[1]);
        Map<String, int[]> durations = new HashMap<>();
        Map<String, Long> releaseOf = new HashMap<>();
        Map<String, Long> dueOf = new HashMap<>();
        Map<String, String> typeOf = new HashMap<>();      // "job/opIndex" -> setup type
        for (int j = 0; j < 6; j++) {
            String id = "J" + j;
            int[] d = {1 + rnd.nextInt(6), 1 + rnd.nextInt(6), 1 + rnd.nextInt(6)};
            durations.put(id, d);
            long release = rnd.nextInt(8); releaseOf.put(id, release);
            long due = 20 + rnd.nextInt(20); dueOf.put(id, due);
            String t0 = rnd.nextBoolean() ? "X" : "Y", t1 = rnd.nextBoolean() ? "X" : "Y";
            typeOf.put(id + "/0", t0);
            typeOf.put(id + "/1", t1);
            builder.job(id, jc -> jc.weight(1 + rnd.nextInt(3)).maxLag(lag)
                    .operation().onAnyOf("A", "B").takes(d[0]).type(t0).releaseAt(release)
                    .then().on("C").takes(d[1]).type(t1)
                    .then().onAnyOf("D", "A").takes(d[2]).dueBy(due));
        }
        JobShopSchedule s = builder.solver().timeLimit(Duration.ofSeconds(30)).workers(8).solve();
        assertThat(s.isFeasible()).isTrue();

        // Per-job: precedence, lag, release, durations, tardiness bookkeeping.
        long totalTardiness = 0;
        for (String job : durations.keySet()) {
            List<ScheduledOperation> ops = s.operationsOf(job);
            assertThat(ops).hasSize(3);
            for (int o = 0; o < 3; o++) {
                assertThat(ops.get(o).end() - ops.get(o).start()).isEqualTo(durations.get(job)[o]);
                assertThat(ops.get(o).index()).isEqualTo(o);
            }
            assertThat(ops.get(0).start()).isGreaterThanOrEqualTo(releaseOf.get(job));
            for (int o = 0; o < 2; o++) {
                long gap = ops.get(o + 1).start() - ops.get(o).end();
                assertThat(gap).as("%s op %d->%d", job, o, o + 1).isBetween(0L, (long) lag);
            }
            assertThat(ops.get(1).machine()).isEqualTo("C");
            assertThat(ops.get(0).machine()).isIn("A", "B");
            assertThat(ops.get(2).machine()).isIn("D", "A");
            long tard = Math.max(0, ops.get(2).end() - dueOf.get(job));
            assertThat(s.tardinessOf(job)).isEqualTo(tard);
            totalTardiness += tard;
            assertThat(s.makespan()).isGreaterThanOrEqualTo(ops.get(2).end());
        }
        assertThat(s.totalTardiness()).isEqualTo(totalTardiness);

        // Per-machine: exclusivity (with at least the default setup between typed ops), downtime.
        for (String m : machines) {
            List<ScheduledOperation> line = s.timelineOf(m);
            for (int i = 0; i + 1 < line.size(); i++) {
                assertThat(line.get(i + 1).start()).as("overlap on %s", m).isGreaterThanOrEqualTo(line.get(i).end());
            }
            for (long[] w : downtimeOf.getOrDefault(m, new long[0][])) {
                for (ScheduledOperation o : line) {
                    assertThat(o.start() < w[1] && o.end() > w[0]).as("%s overlaps downtime %s on %s", o, java.util.Arrays.toString(w), m).isFalse();
                }
            }
        }
        // Setups: ops 0 and 1 are typed. Consecutive typed ops of different types on one machine
        // need the X<->Y changeover (2); same-type pairs pay nothing (the default applies only to
        // unlisted *different* typed pairs, and X<->Y is listed).
        for (String m : List.of("A", "B", "C")) {
            List<ScheduledOperation> typed = new ArrayList<>();
            for (ScheduledOperation o : s.timelineOf(m)) if (o.index() < 2) typed.add(o);
            for (int i = 0; i + 1 < typed.size(); i++) {
                String from = typeOf.get(typed.get(i).job() + "/" + typed.get(i).index());
                String to = typeOf.get(typed.get(i + 1).job() + "/" + typed.get(i + 1).index());
                long gap = typed.get(i + 1).start() - typed.get(i).end();
                if (!from.equals(to)) assertThat(gap).as("changeover %s->%s on %s", from, to, m).isGreaterThanOrEqualTo(setupAB);
            }
        }
        // Gantt view is consistent with the timeline.
        Gantt g = s.asGantt();
        assertThat(g.machines()).containsExactly(machines);
        for (String m : machines) assertThat(g.rowFor(m)).isEqualTo(s.timelineOf(m));
    }

    @Test
    void warningsAreAvailableOnTheBuilderItself() {
        var builder = Optimize.jobShop().machines("M1").setupTime("UNUSED", "ALSO", 1)
                .job("J", j -> j.operation().on("M1").takes(1));
        assertThat(builder.warnings()).hasSize(2);
    }
}

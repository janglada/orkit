package io.github.janglada.orkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-cutting tests for the {@link Solver} / {@link OptimizationProblem}
 * contract shared by every domain: time-limit handling (incl. the sub-second
 * truncation fix and null guard), seed reproducibility, verbose invariance,
 * runtime on infeasible models, fresh-solver-per-call, and default-config solve.
 */
class SolverContractTest {

    // ── F1: sub-second timeLimit no longer truncates to 0; null is rejected ──

    @Test
    void subSecondTimeLimitReturnsResultWithoutHanging() {
        // Before the fix, 800ms truncated to 0s via Duration.toSeconds().
        var sol = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .solver().timeLimit(Duration.ofMillis(800)).workers(1).solve();
        assertThat(sol.runtime().isNegative()).isFalse();
        // 800ms limit (plus solver overhead) must stay well under the old 60s default.
        assertThat(sol.runtime().toMillis()).isLessThanOrEqualTo(5_000);
    }

    @Test
    void nullTimeLimitThrowsModelException() {
        assertThatThrownBy(() -> Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().timeLimit(null).solve())
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("timeLimit");
    }

    // ── Seed reproducibility: same seed + workers => same objective ──────────

    @Test
    void sameSeedProducesSameObjective() {
        var problem = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .build();
        long o1 = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).seed(7).solve().makespan();
        long o2 = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).seed(7).solve().makespan();
        assertThat(o1).isEqualTo(o2);
    }

    // ── verbose(true) does not change the solution ───────────────────────────

    @Test
    void verboseFlagDoesNotChangeObjective() {
        var problem = Optimize.jobShop()
                .machines("M1", "M2", "M3")
                .job("J1", j -> j.operation().on("M1").takes(3).then().on("M2").takes(2).then().on("M3").takes(2))
                .job("J2", j -> j.operation().on("M2").takes(2).then().on("M1").takes(4).then().on("M3").takes(3))
                .job("J3", j -> j.operation().on("M3").takes(4).then().on("M2").takes(3))
                .build();
        long quiet = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).verbose(false).solve().makespan();
        long loud = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).verbose(true).solve().makespan();
        assertThat(loud).isEqualTo(quiet);
    }

    // ── runtime() is reported and non-negative on an infeasible model ────────

    @Test
    void runtimeIsNonNegativeOnInfeasible() {
        var sol = Optimize.cuttingStock()
                .stock("S", s -> s.length(50))
                .required("P", p -> p.length(80))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isFalse();
        assertThat(sol.runtime().isNegative()).isFalse();
    }

    // ── OptimizationProblem.solver() returns a fresh instance each call ─────

    @Test
    void solverReturnsFreshInstanceEachCall() {
        var problem = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .build();
        assertThat(problem.solver()).isNotSameAs(problem.solver());
    }

    // ── Default-config solve (no setter calls) produces a sane result ────────

    @Test
    void defaultConfigSolveProducesFeasibleResult() {
        // No timeLimit/workers/seed/verbose calls — relies on builder defaults.
        var sol = Optimize.jobShop()
                .machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(3))
                .solver().solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.makespan()).isEqualTo(3);
    }

    // ── CpSatSolver: onSolution / warmStart / cancel / workers ───────────────

    private static CpSatProblem<io.github.janglada.orkit.jobshop.JobShopSchedule> hardJobShop(int jobs, int machines, long seed) {
        Random rnd = new Random(seed);
        String[] m = new String[machines];
        for (int i = 0; i < machines; i++) m[i] = "M" + i;
        var b = Optimize.jobShop().machines(m);
        for (int j = 0; j < jobs; j++) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < machines; i++) order.add(i);
            java.util.Collections.shuffle(order, rnd);
            int[] durations = new int[machines];
            for (int i = 0; i < machines; i++) durations[i] = 1 + rnd.nextInt(20);
            b.job("J" + j, jc -> {
                var op = jc.operation().on(m[order.get(0)]).takes(durations[0]);
                for (int i = 1; i < machines; i++) op = op.then().on(m[order.get(i)]).takes(durations[i]);
            });
        }
        return b.build();
    }

    @Test
    void onSolutionReceivesFeasibleIntermediateSolutions() {
        List<io.github.janglada.orkit.jobshop.JobShopSchedule> seen = new ArrayList<>();
        var result = hardJobShop(6, 4, 1).solver()
                .timeLimit(Duration.ofSeconds(10)).workers(1)
                .onSolution(seen::add)
                .solve();
        assertThat(result.isFeasible()).isTrue();
        assertThat(seen).isNotEmpty();
        for (var s : seen) {
            assertThat(s.status()).isEqualTo(SolveStatus.FEASIBLE);
            assertThat(s.objective()).isPresent();
            assertThat(s.operations()).hasSize(24);
        }
        // Intermediate solutions never beat the final one.
        for (var s : seen) assertThat(s.makespan()).isGreaterThanOrEqualTo(result.makespan());
    }

    @Test
    void warmStartWithPreviousSolutionStillOptimal() {
        var problem = hardJobShop(4, 3, 2);
        var first = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(first.isOptimal()).isTrue();
        var second = problem.solver().timeLimit(Duration.ofSeconds(10)).warmStart(first).solve();
        assertThat(second.isOptimal()).isTrue();
        assertThat(second.makespan()).isEqualTo(first.makespan());
    }

    @Test
    void warmStartRejectsNullAndInfeasible() {
        var problem = hardJobShop(2, 2, 3);
        assertThatThrownBy(() -> problem.solver().warmStart(null))
                .isInstanceOf(ModelException.class).hasMessageContaining("warmStart");
        var infeasible = Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(10).deadlineAt(10))
                .job("J2", j -> j.operation().on("M1").takes(10).deadlineAt(10))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(infeasible.isFeasible()).isFalse();
        assertThatThrownBy(() -> problem.solver().warmStart(infeasible))
                .isInstanceOf(ModelException.class).hasMessageContaining("feasible");
    }

    @Test
    void cancelFromListenerStopsEarly() {
        CpSatSolver<io.github.janglada.orkit.jobshop.JobShopSchedule> solver = hardJobShop(12, 8, 4).solver()
                .timeLimit(Duration.ofSeconds(60)).workers(2);
        var result = solver.onSolution(s -> solver.cancel()).solve();
        assertThat(result.isFeasible()).isTrue();
        assertThat(result.runtime().toSeconds()).isLessThan(30);
    }

    @Test
    void listenerExceptionIsWrappedAndStopsSearch() {
        var solver = hardJobShop(6, 4, 5).solver().timeLimit(Duration.ofSeconds(60));
        assertThatThrownBy(() -> solver.onSolution(s -> { throw new IllegalStateException("boom"); }).solve())
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("onSolution")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void workersBelowOneThrows() {
        assertThatThrownBy(() -> hardJobShop(2, 2, 6).solver().workers(0))
                .isInstanceOf(ModelException.class).hasMessageContaining("workers");
    }

    @Test
    void routingSolverHasNoWorkersOrSeed() throws Exception {
        for (var m : io.github.janglada.orkit.routing.RoutingSolver.class.getMethods()) {
            assertThat(m.getName()).as("routing must not accept a knob OR-Tools ignores").isNotIn("workers", "seed");
        }
    }

    // ── Hardening: cancel sessions, concurrent solves, listener errors, limits, overflow ──

    @Test
    void cancelFromAnotherThreadStopsRunningSolve() throws Exception {
        CpSatSolver<io.github.janglada.orkit.jobshop.JobShopSchedule> solver = hardJobShop(15, 10, 7).solver()
                .timeLimit(Duration.ofSeconds(60)).workers(2);
        CountDownLatch firstSolution = new CountDownLatch(1);
        solver.onSolution(s -> firstSolution.countDown());
        var future = CompletableFuture.supplyAsync(solver::solve);
        assertThat(firstSolution.await(30, TimeUnit.SECONDS)).isTrue();
        solver.cancel();
        var result = future.get(20, TimeUnit.SECONDS);
        assertThat(result.isFeasible()).isTrue();
        assertThat(result.runtime().toSeconds()).isLessThan(30);
    }

    @Test
    void concurrentSolveOnOneSolverIsRejected() throws Exception {
        CpSatSolver<io.github.janglada.orkit.jobshop.JobShopSchedule> solver = hardJobShop(15, 10, 8).solver()
                .timeLimit(Duration.ofSeconds(60)).workers(2);
        CountDownLatch running = new CountDownLatch(1);
        solver.onSolution(s -> running.countDown());
        var future = CompletableFuture.supplyAsync(solver::solve);
        assertThat(running.await(30, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(solver::solve).isInstanceOf(ModelException.class).hasMessageContaining("already running");
        solver.cancel();
        assertThat(future.get(20, TimeUnit.SECONDS).isFeasible()).isTrue();
        // The session ended, so the same solver can solve again.
        assertThat(solver.timeLimit(Duration.ofSeconds(1)).solve().status()).isNotNull();
    }

    @Test
    void cancelOnIdleSolverDoesNotAffectNextSolve() {
        var solver = hardJobShop(3, 3, 9).solver().timeLimit(Duration.ofSeconds(10));
        solver.cancel();
        assertThat(solver.solve().isOptimal()).isTrue();
    }

    @Test
    void listenerErrorPropagatesUnwrapped() {
        var solver = hardJobShop(6, 4, 5).solver().timeLimit(Duration.ofSeconds(60));
        assertThatThrownBy(() -> solver.onSolution(s -> { throw new AssertionError("listener bug"); }).solve())
                .isInstanceOf(AssertionError.class)
                .hasMessage("listener bug");
    }

    @Test
    void nonPositiveTimeLimitIsRejected() {
        var problem = hardJobShop(2, 2, 3);
        assertThatThrownBy(() -> problem.solver().timeLimit(Duration.ZERO))
                .isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
        assertThatThrownBy(() -> problem.solver().timeLimit(Duration.ofSeconds(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("timeLimit");
    }

    @Test
    void defaultWorkersNeverExceedAvailableProcessors() {
        assertThat(io.github.janglada.orkit.internal.CpSatParams.DEFAULT_WORKERS)
                .isBetween(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    @Test
    void jobShopHorizonOverflowIsReported() {
        assertThatThrownBy(() -> Optimize.jobShop().machines("M1")
                .job("J1", j -> j.operation().on("M1").takes(5).releaseAt(Long.MAX_VALUE - 1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }

    @Test
    void packingVolumeOverflowIsReported() {
        long huge = 3_000_000_000L;
        assertThatThrownBy(() -> Optimize.packing()
                .container("C", c -> c.length(huge).width(huge).height(huge).maxWeight(10))
                .box("B", b -> b.length(huge).width(huge).height(huge).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("overflows");
    }
}

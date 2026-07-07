package opt;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}

package opt.packing;

import com.google.ortools.Loader;
import opt.ModelException;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingTest {

    static {
        Loader.loadNativeLibraries();
    }

    // ── T-Pk1: Known-optimal packing — 2 containers, 5 small boxes all fit ──────
    // Five 2x2x2 boxes fit easily inside a single 10x10x10 container; with the
    // MinimizeContainersUsed goal the optimum uses exactly one container.

    @Test
    void knownOptimalPacking() {
        PackingSolution sol = new PackingBuilder()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .container("C2", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("B1", b -> b.length(2).width(2).height(2).weight(1))
                .box("B2", b -> b.length(2).width(2).height(2).weight(1))
                .box("B3", b -> b.length(2).width(2).height(2).weight(1))
                .box("B4", b -> b.length(2).width(2).height(2).weight(1))
                .box("B5", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.unassignedBoxes()).isEmpty();
        assertThat(sol.containersUsed()).isEqualTo(1);
        assertNoOverlap3D(sol.assignments());
        assertBoxesWithinBounds(sol.assignments());
    }

    // ── T-Pk2: A box too big for any container is left unassigned ───────────────

    @Test
    void boxTooBigUnassigned() {
        PackingSolution sol = new PackingBuilder()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("big",   b -> b.length(11).width(1).height(1).weight(1))   // 11 > 10 on X
                .box("small", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unassignedBoxes()).containsExactly("big");
        assertThat(sol.containerOf("small")).isEqualTo("C1");
        assertThat(sol.containerOf("big")).isNull();
    }

    // ── T-Pk3: Per-container weight limit is respected ───────────────────────────

    @Test
    void weightLimitRespected() {
        PackingSolution sol = new PackingBuilder()
                .goal(Goals.maximizeVolumeUtilization())
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(5))
                .box("A", b -> b.length(2).width(2).height(2).weight(3))
                .box("B", b -> b.length(2).width(2).height(2).weight(3))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        // total weight 6 > 5, so at most one box can sit in C1
        for (ContainerAssignment a : sol.assignments()) {
            assertThat(a.usedWeight())
                    .as("container %s usedWeight %d exceeds maxWeight %d", a.containerId(), a.usedWeight(), a.maxWeight())
                    .isLessThanOrEqualTo(a.maxWeight());
        }
    }

    // ── T-Pk4: Minimize-containers uses fewer containers than the naive bound ───
    // Three 4x4x4 boxes cannot share a 5x5x5 container (4+4 > 5 on every axis), so
    // each needs its own container. With 4 declared containers, the optimum uses 3.

    @Test
    void minimizeContainersWorks() {
        PackingSolution sol = new PackingBuilder()
                .container("C1", c -> c.length(5).width(5).height(5).maxWeight(100))
                .container("C2", c -> c.length(5).width(5).height(5).maxWeight(100))
                .container("C3", c -> c.length(5).width(5).height(5).maxWeight(100))
                .container("C4", c -> c.length(5).width(5).height(5).maxWeight(100))
                .box("X1", b -> b.length(4).width(4).height(4).weight(1))
                .box("X2", b -> b.length(4).width(4).height(4).weight(1))
                .box("X3", b -> b.length(4).width(4).height(4).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.containersUsed()).isLessThan(4);
        assertThat(sol.containersUsed()).isGreaterThanOrEqualTo(1);
        assertThat(sol.unassignedBoxes()).isEmpty();
    }

    // ── T-Pk5: Maximize-volume utilization yields a positive objective ──────────

    @Test
    void maximizeVolumeUtilization() {
        PackingSolution sol = new PackingBuilder()
                .goal(Goals.maximizeVolumeUtilization())
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("B1", b -> b.length(2).width(2).height(2).weight(1))
                .box("B2", b -> b.length(2).width(2).height(2).weight(1))
                .box("B3", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.objective()).isPresent();
        assertThat(sol.objective().getAsDouble()).isPositive();
    }

    // ── T-Pk6: Duplicate container id throws ModelException ──────────────────────

    @Test
    void duplicateContainerIdThrowsModelException() {
        assertThatThrownBy(() ->
                new PackingBuilder()
                        .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                        .container("C1", c -> c.length(5).width(5).height(5).maxWeight(50))
                        .box("B1", b -> b.length(1).width(1).height(1).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("C1");
    }

    // ── T-Pk7: Duplicate box id throws ModelException ─────────────────────────────

    @Test
    void duplicateBoxIdThrowsModelException() {
        assertThatThrownBy(() ->
                new PackingBuilder()
                        .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                        .box("B1", b -> b.length(1).width(1).height(1).weight(1))
                        .box("B1", b -> b.length(2).width(2).height(2).weight(2))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("B1");
    }

    // ── T-Pk8: Zero dimensions / weight throw ModelException ──────────────────────

    @Test
    void zeroDimensionThrowsModelException() {
        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(0).width(10).height(10).maxWeight(100))
        ).isInstanceOf(ModelException.class).hasMessageContaining("length");

        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(10).width(0).height(10).maxWeight(100))
        ).isInstanceOf(ModelException.class).hasMessageContaining("width");

        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(10).width(10).height(0).maxWeight(100))
        ).isInstanceOf(ModelException.class).hasMessageContaining("height");

        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(10).width(10).height(10).maxWeight(0))
        ).isInstanceOf(ModelException.class).hasMessageContaining("maxWeight");

        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                        .box("B1", b -> b.length(0).width(1).height(1).weight(1))
        ).isInstanceOf(ModelException.class).hasMessageContaining("length");

        assertThatThrownBy(() ->
                new PackingBuilder().container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                        .box("B1", b -> b.length(1).width(1).height(1).weight(0))
        ).isInstanceOf(ModelException.class).hasMessageContaining("weight");
    }

    // ── T-Pk9: No containers throws ModelException ────────────────────────────────

    @Test
    void noContainersThrowsModelException() {
        assertThatThrownBy(() ->
                new PackingBuilder()
                        .box("B1", b -> b.length(1).width(1).height(1).weight(1))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("container");
    }

    // ── T-Pk10: No boxes throws ModelException ────────────────────────────────────

    @Test
    void noBoxesThrowsModelException() {
        assertThatThrownBy(() ->
                new PackingBuilder()
                        .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                        .solver().solve()
        ).isInstanceOf(ModelException.class).hasMessageContaining("box");
    }

    // ── T-Pk11: Reusable problem solved twice with the same builder ──────────────

    @Test
    void reusableBuild() {
        var problem = new PackingBuilder()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .container("C2", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("B1", b -> b.length(2).width(2).height(2).weight(1))
                .box("B2", b -> b.length(3).width(3).height(3).weight(1))
                .box("B3", b -> b.length(2).width(2).height(2).weight(1))
                .build();

        PackingSolution first  = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        PackingSolution second = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(first.isFeasible()).isTrue();
        assertThat(second.isFeasible()).isTrue();
        assertNoOverlap3D(first.assignments());
        assertNoOverlap3D(second.assignments());
    }

    // ── T-Pk12: Medium instance — 10 boxes, 3 containers, solves feasibly ─────────

    @Test
    void mediumInstance() {
        var builder = new PackingBuilder()
                .goal(Goals.maximizeVolumeUtilization())
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(50))
                .container("C2", c -> c.length(10).width(10).height(10).maxWeight(50))
                .container("C3", c -> c.length(10).width(10).height(10).maxWeight(50));
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            int dim = (idx % 3 == 0) ? 4 : 2;
            builder.box("B" + idx, b -> b.length(dim).width(dim).height(dim).weight(2));
        }

        PackingSolution sol = builder.solver()
                .timeLimit(Duration.ofSeconds(15))
                .workers(8)
                .solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.objective()).isPresent();
        assertThat(sol.objective().getAsDouble()).isPositive();
        assertNoOverlap3D(sol.assignments());
        assertBoxesWithinBounds(sol.assignments());
        assertWeightRespected(sol.assignments());
    }

    // ── Structural helpers ───────────────────────────────────────────────────────

    /** Asserts that no two boxes in the same container occupy intersecting 3D space. */
    private void assertNoOverlap3D(List<ContainerAssignment> assignments) {
        for (ContainerAssignment a : assignments) {
            List<ContainerAssignment.BoxPlacement> boxes = a.boxes();
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    ContainerAssignment.BoxPlacement p = boxes.get(i);
                    ContainerAssignment.BoxPlacement q = boxes.get(j);
                    boolean separatedX = (p.x() + p.length()) <= q.x() || (q.x() + q.length()) <= p.x();
                    boolean separatedY = (p.y() + p.width())  <= q.y() || (q.y() + q.width())  <= p.y();
                    boolean separatedZ = (p.z() + p.height()) <= q.z() || (q.z() + q.height()) <= p.z();
                    assertThat(separatedX || separatedY || separatedZ)
                            .as("boxes %s and %s overlap in container %s", p.boxId(), q.boxId(), a.containerId())
                            .isTrue();
                }
            }
        }
    }

    /** Asserts every placed box lies entirely within its container's interior dimensions. */
    private void assertBoxesWithinBounds(List<ContainerAssignment> assignments) {
        for (ContainerAssignment a : assignments) {
            for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                assertThat(p.x() + p.length())
                        .as("box %s exceeds container %s length", p.boxId(), a.containerId())
                        .isLessThanOrEqualTo(a.length());
                assertThat(p.y() + p.width())
                        .as("box %s exceeds container %s width", p.boxId(), a.containerId())
                        .isLessThanOrEqualTo(a.width());
                assertThat(p.z() + p.height())
                        .as("box %s exceeds container %s height", p.boxId(), a.containerId())
                        .isLessThanOrEqualTo(a.height());
                assertThat(p.x()).as("negative x").isGreaterThanOrEqualTo(0);
                assertThat(p.y()).as("negative y").isGreaterThanOrEqualTo(0);
                assertThat(p.z()).as("negative z").isGreaterThanOrEqualTo(0);
            }
        }
    }

    /** Asserts every container's used weight stays within its declared maximum. */
    private void assertWeightRespected(List<ContainerAssignment> assignments) {
        for (ContainerAssignment a : assignments) {
            assertThat(a.usedWeight())
                    .as("container %s exceeds its maxWeight", a.containerId())
                    .isLessThanOrEqualTo(a.maxWeight());
        }
    }
}

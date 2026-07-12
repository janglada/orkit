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

    // ── T-Pk13: Negative dimensions/weights are rejected ────────────────────

    @Test
    void negativeDimensionsThrowModelException() {
        assertThatThrownBy(() -> new PackingBuilder().container("C", c -> c.length(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new PackingBuilder().box("B", b -> b.length(-1)))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new PackingBuilder()
                .box("B", b -> b.length(2).width(2).height(2).weight(-1)))
                .isInstanceOf(ModelException.class);
    }

    // ── T-Pk14: Blank container/box ids are rejected ────────────────────────

    @Test
    void blankIdsThrowModelException() {
        assertThatThrownBy(() -> new PackingBuilder()
                .container("", c -> c.length(10).width(10).height(10).maxWeight(10)))
                .isInstanceOf(ModelException.class).hasMessageContaining("container id");
        assertThatThrownBy(() -> new PackingBuilder()
                .box("", b -> b.length(2).width(2).height(2).weight(1)))
                .isInstanceOf(ModelException.class).hasMessageContaining("box id");
    }

    // ── T-Pk15: goal(null) is rejected ──────────────────────────────────────

    @Test
    void nullGoalThrowsModelException() {
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(10))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal");
    }

    // ── T-Pk16: Single box, single container; exact-fit boundary ────────────

    @Test
    void singleBoxSingleContainerAndExactFitBoundary() {
        PackingSolution trivial = new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(10))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(trivial.isFeasible()).isTrue();
        assertThat(trivial.containersUsed()).isEqualTo(1);

        // Box dimensions exactly equal the container on every axis must still fit.
        PackingSolution exact = new PackingBuilder()
                .container("C", c -> c.length(5).width(5).height(5).maxWeight(10))
                .box("B", b -> b.length(5).width(5).height(5).weight(5))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(exact.unassignedBoxes()).isEmpty();
        assertThat(exact.containerOf("B")).isEqualTo("C");
    }

    // ── T-Pk17: Default goal minimizes containers used ───────────────────────

    @Test
    void defaultGoalMinimizesContainersUsed() {
        // No .goal(...) call: default is minimizeContainersUsed => both boxes in 1 container.
        PackingSolution sol = new PackingBuilder()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(100))
                .container("C2", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("B1", b -> b.length(2).width(2).height(2).weight(1))
                .box("B2", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.containersUsed()).isEqualTo(1);
        assertThat(sol.unassignedBoxes()).isEmpty();
    }

    // ── T-Pk18: Empty declared container appears in assignments() ────────────

    @Test
    void emptyContainerAppearsInAssignments() {
        PackingSolution sol = new PackingBuilder()
                .container("C1", c -> c.length(10).width(10).height(10).maxWeight(10))
                .container("C2", c -> c.length(10).width(10).height(10).maxWeight(10))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        // Both declared containers appear; exactly one is empty (solver picks which to fill).
        assertThat(sol.assignments()).hasSize(2);
        assertThat(sol.containersUsed()).isEqualTo(1);
        long emptyCount = sol.assignments().stream().filter(a -> a.boxes().isEmpty()).count();
        assertThat(emptyCount).isEqualTo(1);
        ContainerAssignment empty = sol.assignments().stream()
                .filter(a -> a.boxes().isEmpty()).findFirst().orElseThrow();
        assertThat(empty.usedVolume()).isZero();
        assertThat(empty.usedWeight()).isZero();
    }

    // ── T-Pk19: Unknown accessors return null ───────────────────────────────

    @Test
    void unknownAccessorsReturnNull() {
        PackingSolution sol = new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(10))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.assignmentOf("NOPE")).isNull();
        assertThat(sol.containerOf("NOPE")).isNull();
    }

    // ── T-Pk20: Re-solve yields the same objective ──────────────────────────

    @Test
    void reusableBuildYieldsSameObjective() {
        var problem = new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("B1", b -> b.length(2).width(2).height(2).weight(1))
                .box("B2", b -> b.length(3).width(3).height(3).weight(2))
                .build();
        PackingSolution s1 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        PackingSolution s2 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(s1.objective()).isEqualTo(s2.objective());
    }

    // ── T-Pk21: Rotation lets a box fit by turning 90° ────────────────────────
    // Box (5,2,2) cannot fit a (3,6,6) container in its canonical orientation
    // (length 5 > 3 on X), but rotating to (2,5,2) or (2,2,5) fits every axis.

    @Test
    void rotationLetsBoxFit() {
        PackingSolution noRotate = new PackingBuilder()
                .goal(Goals.maximizeVolumeUtilization())
                .container("C", c -> c.length(3).width(6).height(6).maxWeight(100))
                .box("B", b -> b.length(5).width(2).height(2).weight(1))   // not rotatable
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(noRotate.isFeasible()).isTrue();
        assertThat(noRotate.containerOf("B")).isNull();   // cannot fit without rotation

        PackingSolution withRotate = new PackingBuilder()
                .goal(Goals.maximizeVolumeUtilization())
                .container("C", c -> c.length(3).width(6).height(6).maxWeight(100))
                .box("B", b -> b.length(5).width(2).height(2).weight(1).rotatable(true))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(withRotate.isFeasible()).isTrue();
        assertThat(withRotate.containerOf("B")).isEqualTo("C");
        ContainerAssignment.BoxPlacement p = placementOf(withRotate, "B");
        assertThat(p).isNotNull();
        assertThat(p.orientation()).as("rotatable box must report a chosen orientation").isNotNull();
        // The oriented dimensions must fit the container on every axis.
        assertThat(p.x() + p.length()).isLessThanOrEqualTo(3);
        assertThat(p.y() + p.width()).isLessThanOrEqualTo(6);
        assertThat(p.z() + p.height()).isLessThanOrEqualTo(6);
        assertBoxesWithinBounds(withRotate.assignments());
    }

    // ── T-Pk22: Support footprint rejects a floating box ───────────────────────
    // A=(4,2,2) fills the X axis, B=(2,4,2) fills the Y axis of a (4,4,4) container,
    // so neither side-by-side nor footprint-containment is possible: they MUST stack
    // yet neither footprint can hold the other. With requireSupport this is infeasible;
    // without it the boxes may float and the stack is feasible.

    @Test
    void supportRejectsFloatingBox() {
        PackingSolution floating = new PackingBuilder()
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(4).width(4).height(4).maxWeight(100))
                .box("A", b -> b.length(4).width(2).height(2).weight(1))
                .box("B", b -> b.length(2).width(4).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(floating.isFeasible()).isTrue();
        assertThat(floating.containersUsed()).isEqualTo(1);

        PackingSolution supported = new PackingBuilder()
                .requireSupport(true)
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(4).width(4).height(4).maxWeight(100))
                .box("A", b -> b.length(4).width(2).height(2).weight(1))
                .box("B", b -> b.length(2).width(4).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(supported.isFeasible()).as("support footprint makes the floating stack impossible").isFalse();
    }

    // ── T-Pk23: Stacking precedence — above() forces vertical order ────────────

    @Test
    void precedenceAboveForcesOrder() {
        PackingSolution sol = new PackingBuilder()
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).above("B"))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(sol.isFeasible()).isTrue();
        ContainerAssignment.BoxPlacement a = placementOf(sol, "A");
        ContainerAssignment.BoxPlacement b = placementOf(sol, "B");
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        // A is declared above B: A's bottom must sit at or above B's top.
        assertThat(a.z())
                .as("A (declared above B) must rest at or above B's top")
                .isGreaterThanOrEqualTo(b.z() + b.height());
        assertNoOverlap3D(sol.assignments());
    }

    // ── T-Pk24: Weight-bearing cap — overload is infeasible, within-cap feasible ─

    @Test
    void weightBearingCap() {
        // Two 4x4-footprint boxes in a 4x4 container must stack. Precedence (A below B)
        // forces B on top of A, so A bears B's weight.
        PackingSolution feasible = new PackingBuilder()
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(4).width(4).height(4).maxWeight(100))
                .box("A", b -> b.length(4).width(4).height(2).weight(1)
                        .maxStackedWeight(10).below("B"))
                .box("B", b -> b.length(4).width(4).height(2).weight(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(feasible.isFeasible()).as("load 5 <= cap 10 must be feasible").isTrue();

        PackingSolution overloaded = new PackingBuilder()
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(4).width(4).height(4).maxWeight(100))
                .box("A", b -> b.length(4).width(4).height(2).weight(1)
                        .maxStackedWeight(3).below("B"))
                .box("B", b -> b.length(4).width(4).height(2).weight(5))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(overloaded.isFeasible()).as("load 5 > cap 3 must be infeasible").isFalse();
    }

    // ── T-Pk25: Validation — bad maxStackedWeight / unknown / contradictory ────

    @Test
    void validationRejectsBadStackingConfig() {
        // negative maxStackedWeight
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).maxStackedWeight(-1))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");

        // above() referencing an unknown box id
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).above("NOPE"))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown box id");

        // below() referencing an unknown box id
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).below("NOPE"))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown box id");

        // contradictory: A above B and A below B
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).above("B").below("B"))
                .box("B", b -> b.length(2).width(2).height(2).weight(1))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("ontradict");

        // contradictory: A above B and B above A
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).above("B"))
                .box("B", b -> b.length(2).width(2).height(2).weight(1).above("A"))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("ontradict");

        // self-reference
        assertThatThrownBy(() -> new PackingBuilder()
                .container("C", c -> c.length(10).width(10).height(10).maxWeight(100))
                .box("A", b -> b.length(2).width(2).height(2).weight(1).above("A"))
                .solver().solve())
                .isInstanceOf(ModelException.class);
    }

    // ── Helpers for the stacking tests ─────────────────────────────────────────

    /** Returns the placement of the given box id, or {@code null} if unassigned. */
    private ContainerAssignment.BoxPlacement placementOf(PackingSolution sol, String boxId) {
        for (ContainerAssignment a : sol.assignments()) {
            for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                if (p.boxId().equals(boxId)) return p;
            }
        }
        return null;
    }
}

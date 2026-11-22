package io.github.janglada.orkit.packing;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.SolveStatus;
import io.github.janglada.orkit.packing.ContainerAssignment.BoxPlacement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Scale of the shared-position model, symmetry breaking, and real support under {@code requireSupport}. */
class PackingCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    /** No overlap, inside the container, within the weight limit. */
    static void assertValidPacking(PackingSolution sol) {
        for (ContainerAssignment a : sol.assignments()) {
            List<BoxPlacement> ps = a.boxes();
            for (BoxPlacement p : ps) {
                assertThat(p.x()).isNotNegative();
                assertThat(p.y()).isNotNegative();
                assertThat(p.z()).isNotNegative();
                assertThat(p.x() + p.length()).isLessThanOrEqualTo(a.length());
                assertThat(p.y() + p.width()).isLessThanOrEqualTo(a.width());
                assertThat(p.z() + p.height()).isLessThanOrEqualTo(a.height());
            }
            assertThat(a.usedWeight()).isLessThanOrEqualTo(a.maxWeight());
            for (int i = 0; i < ps.size(); i++) {
                for (int j = i + 1; j < ps.size(); j++) {
                    BoxPlacement p = ps.get(i), q = ps.get(j);
                    boolean apart = p.x() + p.length() <= q.x() || q.x() + q.length() <= p.x()
                            || p.y() + p.width() <= q.y() || q.y() + q.width() <= p.y()
                            || p.z() + p.height() <= q.z() || q.z() + q.height() <= p.z();
                    assertThat(apart).as("%s and %s overlap in %s", p.boxId(), q.boxId(), a.containerId()).isTrue();
                }
            }
        }
    }

    /** Every box stands on the floor or flush on one box whose footprint contains its own. */
    static void assertSupported(PackingSolution sol) {
        for (ContainerAssignment a : sol.assignments()) {
            for (BoxPlacement p : a.boxes()) {
                if (p.z() == 0) continue;
                boolean resting = a.boxes().stream().anyMatch(q -> q != p
                        && q.z() + q.height() == p.z()
                        && q.x() <= p.x() && p.x() + p.length() <= q.x() + q.length()
                        && q.y() <= p.y() && p.y() + p.width() <= q.y() + q.width());
                assertThat(resting).as("%s at z=%d in %s rests on nothing", p.boxId(), p.z(), a.containerId()).isTrue();
            }
        }
    }

    @Test
    void thirtyRotatableBoxesProveTheirOptimum() {
        Random rnd = new Random(7);
        PackingBuilder pb = Optimize.packing().goal(Goals.minimizeContainersUsed());
        for (int c = 0; c < 5; c++) pb.container("C" + c, k -> k.length(120).width(100).height(100).maxWeight(1_000_000));
        for (int i = 0; i < 30; i++) {
            int l = 20 + rnd.nextInt(40), w = 20 + rnd.nextInt(40), h = 20 + rnd.nextInt(40);
            pb.box("B" + i, b -> b.length(l).width(w).height(h).weight(10).rotatable(true));
        }
        PackingSolution sol = pb.solver().timeLimit(LIMIT).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.containersUsed()).isEqualTo(2);
        assertThat(sol.unassignedBoxes()).isEmpty();
        assertValidPacking(sol);
    }

    @Test
    void identicalBoxesAndContainersKeepTheOptimum() {
        PackingBuilder pb = Optimize.packing()
                .goal(Goals.maximizeVolumeUtilization());
        for (int c = 0; c < 3; c++) pb.container("C" + c, k -> k.length(10).width(10).height(5).maxWeight(100));
        for (int i = 0; i < 14; i++) pb.box("Cube" + i, b -> b.length(5).width(5).height(5).weight(1));
        PackingSolution sol = pb.solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.objective()).hasValue(12 * 125);
        assertThat(sol.unassignedBoxes()).hasSize(2);
        assertThat(sol.unassignedBoxes()).containsExactly("Cube12", "Cube13");
        assertValidPacking(sol);
    }

    @Test
    void supportedBoxesNeverHover() {
        // A hint that puts the only box in mid-air is overridden: support pulls it to the floor.
        BoxPlacement hovering = new BoxPlacement("Solo", 0, 0, 5, 2, 2, 2, null);
        PackingSolution hint = new PackingSolution(SolveStatus.FEASIBLE, OptionalDouble.of(1), Duration.ZERO,
                List.of(new ContainerAssignment("C", 4, 4, 10, 100, List.of(hovering), 8, 1)), List.of());
        PackingSolution sol = Optimize.packing().requireSupport(true)
                .goal(Goals.minimizeContainersUsed())
                .container("C", c -> c.length(4).width(4).height(10).maxWeight(100))
                .box("Solo", b -> b.length(2).width(2).height(2).weight(1))
                .solver().workers(1).warmStart(hint).timeLimit(LIMIT).solve();
        assertThat(sol.assignments().get(0).boxes().get(0).z()).isZero();
    }

    @Test
    void supportStacksFlushOnContainingFootprints() {
        // Floor space for two bases only; everything else must stack flush on a wider box.
        PackingSolution sol = Optimize.packing().requireSupport(true)
                .goal(Goals.maximizeVolumeUtilization())
                .container("C", c -> c.length(8).width(4).height(9).maxWeight(1000))
                .box("Base1", b -> b.length(4).width(4).height(3).weight(5))
                .box("Base2", b -> b.length(4).width(4).height(3).weight(5))
                .box("Mid", b -> b.length(4).width(4).height(2).weight(3))
                .box("Small1", b -> b.length(2).width(2).height(2).weight(1))
                .box("Small2", b -> b.length(2).width(2).height(2).weight(1))
                .box("Small3", b -> b.length(2).width(2).height(4).weight(1).rotatable(true))
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.unassignedBoxes()).isEmpty();
        assertValidPacking(sol);
        assertSupported(sol);
    }

    @Test
    void supportAcrossSeveralContainers() {
        PackingSolution sol = Optimize.packing().requireSupport(true)
                .goal(Goals.minimizeContainersUsed())
                .container("Small", c -> c.length(3).width(3).height(3).maxWeight(100))
                .container("Big", c -> c.length(6).width(6).height(6).maxWeight(100))
                .box("Wide", b -> b.length(6).width(6).height(1).weight(1))
                .box("A", b -> b.length(3).width(3).height(3).weight(1))
                .box("B", b -> b.length(3).width(3).height(3).weight(1).above("Wide"))
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.containersUsed()).isEqualTo(1);
        assertValidPacking(sol);
        assertSupported(sol);
    }
}

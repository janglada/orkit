package io.github.janglada.orkit.packing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coverage of packing warm starts with rotation and of the builder validation branches. */
class PackingFeaturesTest {

    @Test
    void warmStartWithRotatableBoxesReproducesTheOptimum() {
        var problem = Optimize.packing()
                .container("C", c -> c.length(6).width(6).height(6).maxWeight(100))
                .box("Tall", b -> b.length(2).width(2).height(6).weight(1).rotatable(true))
                .box("Flat", b -> b.length(6).width(6).height(2).weight(1).rotatable(true))
                .box("Cube", b -> b.length(2).width(2).height(2).weight(1))
                .box("Huge", b -> b.length(9).width(9).height(9).weight(1))   // never fits => x forced 0
                .goal(Goals.maximizeVolumeUtilization())
                .build();
        var first = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(first.isOptimal()).isTrue();
        assertThat(first.unassignedBoxes()).containsExactly("Huge");
        var again = problem.solver().timeLimit(Duration.ofSeconds(10)).workers(1).warmStart(first).solve();
        assertThat(again.isOptimal()).isTrue();
        assertThat(again.objective()).isEqualTo(first.objective());
        assertThat(again.runtime().isNegative()).isFalse();
    }

    @Test
    void containerAndBoxValidationBranches() {
        assertThatThrownBy(() -> Optimize.packing().container("C", c -> c.width(1).height(1).maxWeight(1))).isInstanceOf(ModelException.class).hasMessageContaining("length");
        assertThatThrownBy(() -> Optimize.packing().container("C", c -> c.length(1).height(1).maxWeight(1))).isInstanceOf(ModelException.class).hasMessageContaining("width");
        assertThatThrownBy(() -> Optimize.packing().container("C", c -> c.length(1).width(1).maxWeight(1))).isInstanceOf(ModelException.class).hasMessageContaining("height");
        assertThatThrownBy(() -> Optimize.packing().container("C", c -> c.length(1).width(1).height(1))).isInstanceOf(ModelException.class).hasMessageContaining("maxWeight");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.width(1).height(1).weight(1))).isInstanceOf(ModelException.class).hasMessageContaining("length");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.length(1).height(1).weight(1))).isInstanceOf(ModelException.class).hasMessageContaining("width");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.length(1).width(1).weight(1))).isInstanceOf(ModelException.class).hasMessageContaining("height");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.length(1).width(1).height(1))).isInstanceOf(ModelException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.width(0))).isInstanceOf(ModelException.class).hasMessageContaining("width()");
        assertThatThrownBy(() -> Optimize.packing().box("B", b -> b.height(0))).isInstanceOf(ModelException.class).hasMessageContaining("height()");
    }

    @Test
    void warmStartFromADifferentModelIgnoresUnknownIds() {
        var previous = Optimize.packing()
                .container("Old", c -> c.length(6).width(6).height(6).maxWeight(100))
                .box("Gone", b -> b.length(2).width(2).height(2).weight(1))
                .box("Cube", b -> b.length(2).width(2).height(2).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        var sol = Optimize.packing()
                .container("C", c -> c.length(6).width(6).height(6).maxWeight(100))
                .box("Cube", b -> b.length(2).width(2).height(2).weight(1))
                .box("New", b -> b.length(3).width(3).height(3).weight(1))
                .solver().timeLimit(Duration.ofSeconds(5)).warmStart(previous).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.unassignedBoxes()).isEmpty();
    }
}

package opt.knapsack;

import opt.ModelException;
import opt.Optimize;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Builder validation branches for bins and items. */
class KnapsackFeaturesTest {

    @Test
    void binAndItemMustBeFullyConfigured() {
        assertThatThrownBy(() -> Optimize.multipleKnapsack().bin("B", b -> {})).isInstanceOf(ModelException.class).hasMessageContaining(".capacity(");
        assertThatThrownBy(() -> Optimize.multipleKnapsack().item("I", i -> i.weight(1))).isInstanceOf(ModelException.class).hasMessageContaining(".profit(");
        assertThatThrownBy(() -> Optimize.multipleKnapsack().item("I", i -> i.profit(1))).isInstanceOf(ModelException.class).hasMessageContaining(".weight(");
    }
}

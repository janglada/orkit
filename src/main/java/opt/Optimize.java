package opt;

import com.google.ortools.Loader;
import opt.jobshop.JobShopBuilder;
import opt.knapsack.MultipleKnapsackBuilder;
import opt.routing.RoutingBuilder;
import opt.scheduling.SchedulingBuilder;

public final class Optimize {

    static {
        Loader.loadNativeLibraries();
    }

    public static SchedulingBuilder scheduling() {
        return new SchedulingBuilder();
    }

    public static JobShopBuilder jobShop() {
        return new JobShopBuilder();
    }

    public static RoutingBuilder vehicleRouting() {
        return new RoutingBuilder();
    }

    public static MultipleKnapsackBuilder multipleKnapsack() {
        return new MultipleKnapsackBuilder();
    }

    private Optimize() {}
}

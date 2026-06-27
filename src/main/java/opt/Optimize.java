package opt;

import com.google.ortools.Loader;
import opt.jobshop.JobShopBuilder;
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

    private Optimize() {}
}

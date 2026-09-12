package opt;

import com.google.ortools.Loader;
import opt.booking.BookingBuilder;
import opt.cutting.CuttingBuilder;
import opt.jobshop.JobShopBuilder;
import opt.knapsack.MultipleKnapsackBuilder;
import opt.packing.PackingBuilder;
import opt.routing.RoutingBuilder;
import opt.scheduling.SchedulingBuilder;

/**
 * Entry point for building and solving operations-research problems with OR-Tools.
 * <p>
 * Each factory method returns a fluent builder for a specific problem family; obtain a
 * {@link Solver} from the builder via {@link OptimizationProblem#solver()}.
 */
public final class Optimize {

    static {
        Loader.loadNativeLibraries();
    }

    /** Creates a builder for employee shift scheduling problems. */
    public static SchedulingBuilder scheduling() {
        return new SchedulingBuilder();
    }

    /** Creates a builder for job shop scheduling problems. */
    public static JobShopBuilder jobShop() {
        return new JobShopBuilder();
    }

    /** Creates a builder for capacitated vehicle routing problems. */
    public static RoutingBuilder vehicleRouting() {
        return new RoutingBuilder();
    }

    /** Creates a builder for multiple-knapsack assignment problems. */
    public static MultipleKnapsackBuilder multipleKnapsack() {
        return new MultipleKnapsackBuilder();
    }

    /** Creates a builder for container-loading / 3D bin packing problems. */
    public static PackingBuilder packing() {
        return new PackingBuilder();
    }

    /** Creates a builder for resource-booking / appointment-scheduling problems. */
    public static BookingBuilder booking() {
        return new BookingBuilder();
    }

    /** Creates a builder for cutting stock problems (1D rolls or 2D non-guillotine sheets). */
    public static CuttingBuilder cuttingStock() {
        return new CuttingBuilder();
    }

    private Optimize() {}
}

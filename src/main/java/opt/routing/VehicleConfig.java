package opt.routing;

/** Fluent configurator for a vehicle, used inside the lambda passed to
 *  {@link RoutingBuilder#vehicle(String, java.util.function.Consumer)}. */
public final class VehicleConfig {

    String id;
    int capacity = Integer.MAX_VALUE;
    long maxDistanceMeters = 0;

    VehicleConfig() {}

    public VehicleConfig capacity(int demand) {
        this.capacity = demand;
        return this;
    }

    public VehicleConfig maxDistanceMeters(long meters) {
        this.maxDistanceMeters = meters;
        return this;
    }
}

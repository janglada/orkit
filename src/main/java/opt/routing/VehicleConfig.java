package opt.routing;

import opt.ModelException;

/** Fluent configurator for a vehicle, used inside the lambda passed to
 *  {@link RoutingBuilder#vehicle(String, java.util.function.Consumer)}. */
public final class VehicleConfig {

    String id;
    int capacity = Integer.MAX_VALUE;
    long maxDistanceMeters = 0;

    VehicleConfig() {}

    public VehicleConfig capacity(int units) {
        if (units < 1) throw new ModelException("capacity must be >= 1, got: " + units);
        this.capacity = units;
        return this;
    }

    public VehicleConfig maxDistanceMeters(long meters) {
        if (meters < 0) throw new ModelException("maxDistanceMeters must be >= 0, got: " + meters);
        this.maxDistanceMeters = meters;
        return this;
    }
}

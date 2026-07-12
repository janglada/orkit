package opt.routing;

import opt.ModelException;
import opt.internal.Validate;

/** Fluent configurator for a vehicle, used inside the lambda passed to
 *  {@link RoutingBuilder#vehicle(String, java.util.function.Consumer)}. */
public final class VehicleConfig {

    String id;
    int capacity = Integer.MAX_VALUE;
    long maxDistanceMeters = 0;
    String startDepotId;
    String endDepotId;

    VehicleConfig() {}

    /** Sets the vehicle's carrying capacity in arbitrary demand units; default unlimited. */
    public VehicleConfig capacity(int units) {
        if (units < 1) throw new ModelException("capacity must be >= 1, got: " + units);
        this.capacity = units;
        return this;
    }

    /** Caps the total distance this vehicle may travel; default 0 means no cap. */
    public VehicleConfig maxDistanceMeters(long meters) {
        if (meters < 0) throw new ModelException("maxDistanceMeters must be >= 0, got: " + meters);
        this.maxDistanceMeters = meters;
        return this;
    }

    /**
     * Assigns this vehicle to depart from the given depot. When {@link #endsAt(String)}
     * is not also set, the vehicle returns to this same depot. If neither {@code startsAt}
     * nor {@code endsAt} is called, the vehicle uses the default (first) depot for both ends.
     *
     * @param depotId id of a depot registered via {@link RoutingBuilder#depot(String, double, double)}
     * @throws opt.ModelException if {@code depotId} is blank
     */
    public VehicleConfig startsAt(String depotId) {
        Validate.nonBlank(depotId, "startsAt depot id");
        this.startDepotId = depotId;
        return this;
    }

    /**
     * Assigns this vehicle to return to the given depot. When {@link #startsAt(String)}
     * is not also set, the vehicle departs from this same depot. If neither {@code startsAt}
     * nor {@code endsAt} is called, the vehicle uses the default (first) depot for both ends.
     *
     * @param depotId id of a depot registered via {@link RoutingBuilder#depot(String, double, double)}
     * @throws opt.ModelException if {@code depotId} is blank
     */
    public VehicleConfig endsAt(String depotId) {
        Validate.nonBlank(depotId, "endsAt depot id");
        this.endDepotId = depotId;
        return this;
    }
}

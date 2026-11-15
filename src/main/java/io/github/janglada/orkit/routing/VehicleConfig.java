package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fluent configurator for a vehicle, used inside the lambda passed to
 *  {@link RoutingBuilder#vehicle(String, java.util.function.Consumer)}. */
public final class VehicleConfig {

    String id;
    int capacity = Integer.MAX_VALUE;
    long maxDistanceMeters = 0;
    String startDepotId;
    String endDepotId;
    int availableFromMin = 0;
    int availableUntilMin = 24 * 60;
    boolean fromSet = false;
    boolean untilSet = false;
    long maxRouteDurationMin = 0;
    long fixedCost = 0;
    final Set<String> skills = new LinkedHashSet<>();

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
     * @throws io.github.janglada.orkit.ModelException if {@code depotId} is blank
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
     * @throws io.github.janglada.orkit.ModelException if {@code depotId} is blank
     */
    public VehicleConfig endsAt(String depotId) {
        Validate.nonBlank(depotId, "endsAt depot id");
        this.endDepotId = depotId;
        return this;
    }

    /**
     * Earliest time-of-day the vehicle may leave its start depot; default 00:00. Turns the
     * time dimension on, so arrival times are then reported on the route.
     */
    public VehicleConfig availableFrom(LocalTime time) {
        if (time == null) throw new ModelException("availableFrom must not be null.");
        this.availableFromMin = time.toSecondOfDay() / 60;
        this.fromSet = true;
        validateWindow();
        return this;
    }

    /** Latest time-of-day the vehicle must be back at its end depot; default end of day. */
    public VehicleConfig availableUntil(LocalTime time) {
        if (time == null) throw new ModelException("availableUntil must not be null.");
        this.availableUntilMin = time.toSecondOfDay() / 60;
        this.untilSet = true;
        validateWindow();
        return this;
    }

    /**
     * Caps the route duration (end-depot arrival minus start-depot departure), which counts
     * driving, service, and waiting time.
     */
    public VehicleConfig maxRouteDuration(Duration duration) {
        if (duration == null) throw new ModelException("maxRouteDuration must not be null.");
        Validate.positive(duration.toMinutes(), "maxRouteDuration minutes");
        this.maxRouteDurationMin = duration.toMinutes();
        return this;
    }

    /**
     * Cost charged once when this vehicle serves at least one stop; default 0. Same units as
     * meters, so it is reported in {@link RoutingPlan#objective()} under {@link Goals#totalCost()}.
     * Idle vehicles pay nothing.
     */
    public VehicleConfig fixedCost(long cost) {
        Validate.nonNegative(cost, "fixedCost");
        this.fixedCost = cost;
        return this;
    }

    /** Capabilities of this vehicle (for example "refrigerated"), matched against {@link StopConfig#requires(String...)}. */
    public VehicleConfig skills(String... skills) {
        for (String s : skills) Validate.nonBlank(s, "vehicle skill");
        this.skills.addAll(Arrays.asList(skills));
        return this;
    }

    private void validateWindow() {
        if (fromSet && untilSet && availableFromMin >= availableUntilMin) {
            throw new ModelException("Vehicle window is invalid: availableFrom must be strictly before availableUntil");
        }
    }
}

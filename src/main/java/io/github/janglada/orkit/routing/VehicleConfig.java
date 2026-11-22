package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.time.LocalTime;
import io.github.janglada.orkit.routing.internal.BreakDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fluent configurator for a vehicle, used inside the lambda passed to
 *  {@link RoutingBuilder#vehicle(String, java.util.function.Consumer)}. */
public final class VehicleConfig {

    String id;
    final Map<String, Long> capacities = new LinkedHashMap<>();
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
    final List<BreakDef> breaks = new ArrayList<>();
    long costPerMeter = 1;
    long costPerMinute = 0;

    VehicleConfig() {}

    /**
     * Sets the vehicle's carrying capacity in the default {@value RoutingBuilder#DEFAULT_DIMENSION}
     * dimension, in the units of {@link StopConfig#demand(long)}; default unlimited.
     */
    public VehicleConfig capacity(long units) {
        return capacity(RoutingBuilder.DEFAULT_DIMENSION, units);
    }

    /**
     * Sets the vehicle's capacity in a named dimension (for example {@code "kg"} or
     * {@code "pallets"}), matched against {@link StopConfig#demand(String, long)}. A dimension
     * this vehicle does not declare is unbounded for it.
     */
    public VehicleConfig capacity(String dimension, long units) {
        Validate.nonBlank(dimension, "capacity dimension");
        if (units < 1) throw new ModelException("capacity must be >= 1, got: " + units);
        this.capacities.put(dimension, units);
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

    /**
     * Requires a driver break of {@code duration} starting no earlier than {@code earliest} and
     * ending no later than {@code latest}. The break may interrupt a drive between two stops
     * (never a service), or fall before the route starts or after it ends. Repeatable; turns the
     * time dimension on.
     *
     * @throws ModelException if an argument is null, the duration is not positive, or the
     *                        break does not fit between {@code earliest} and {@code latest}
     */
    public VehicleConfig breakBetween(LocalTime earliest, LocalTime latest, Duration duration) {
        if (earliest == null || latest == null || duration == null) {
            throw new ModelException("breakBetween arguments must not be null.");
        }
        Validate.positive(duration.toMinutes(), "break duration minutes");
        int from = earliest.toSecondOfDay() / 60;
        int until = latest.toSecondOfDay() / 60;
        int length = (int) duration.toMinutes();
        if (from + length > until) {
            throw new ModelException("Break of " + length + " min does not fit between " + earliest + " and " + latest + ".");
        }
        breaks.add(new BreakDef(from, until - length, length));
        return this;
    }

    /**
     * Cost per meter driven under {@link Goals#totalCost()}; default 1, so meters and cost
     * coincide unless set. Other goals measure plain meters.
     */
    public VehicleConfig costPerMeter(long cost) {
        Validate.nonNegative(cost, "costPerMeter");
        this.costPerMeter = cost;
        return this;
    }

    /**
     * Cost per minute of route duration (start-depot departure to end-depot arrival, waiting
     * included) under {@link Goals#totalCost()}; default 0. Turns the time dimension on.
     */
    public VehicleConfig costPerMinute(long cost) {
        Validate.nonNegative(cost, "costPerMinute");
        this.costPerMinute = cost;
        return this;
    }

    private void validateWindow() {
        if (fromSet && untilSet && availableFromMin >= availableUntilMin) {
            throw new ModelException("Vehicle window is invalid: availableFrom must be strictly before availableUntil");
        }
    }
}

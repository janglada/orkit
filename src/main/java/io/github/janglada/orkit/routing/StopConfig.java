package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.internal.Validate;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fluent configurator for a stop, used inside the lambda passed to
 *  {@link RoutingBuilder#stop(String, java.util.function.Consumer)}. */
public final class StopConfig {

    String id;
    double lat;
    double lon;
    int demand = 0;
    int serviceTimeMin = 0;
    int openMin = 0;
    int closeMin = 24 * 60;
    boolean atSet = false;
    boolean openSet = false;
    boolean closeSet = false;
    long dropPenalty = 0;
    final Set<String> requiredSkills = new LinkedHashSet<>();

    StopConfig() {}

    /** Sets the geographic location of this stop (required when using haversine distances). */
    public StopConfig at(double lat, double lon) {
        validateCoordinates(lat, lon, "Stop '" + id + "'");
        this.lat = lat;
        this.lon = lon;
        this.atSet = true;
        return this;
    }

    /**
     * Sets the demand picked up or delivered at this stop, in the same units as vehicle capacity.
     * For the delivery stop of a {@link RoutingBuilder#pickupAndDelivery(String, String) pair},
     * leave it unset (or equal to the pickup's demand); the load is subtracted on delivery.
     */
    public StopConfig demand(int units) {
        if (units < 0) throw new ModelException("demand must be >= 0, got: " + units);
        this.demand = units;
        return this;
    }

    /** Sets the dwell time the vehicle spends at this stop before departing. */
    public StopConfig serviceTime(Duration duration) {
        if (duration == null) throw new ModelException("serviceTime must not be null.");
        if (duration.isNegative()) throw new ModelException("serviceTime must be non-negative");
        this.serviceTimeMin = (int) duration.toMinutes();
        return this;
    }

    /** Sets the earliest time-of-day the stop may be served; defaults to 00:00. */
    public StopConfig openFrom(LocalTime time) {
        if (time == null) throw new ModelException("openFrom must not be null.");
        this.openMin = time.toSecondOfDay() / 60;
        this.openSet = true;
        validateWindow();
        return this;
    }

    /** Sets the latest time-of-day the stop may be served; defaults to 23:59 (end of day). */
    public StopConfig closeAt(LocalTime time) {
        if (time == null) throw new ModelException("closeAt must not be null.");
        this.closeMin = time.toSecondOfDay() / 60;
        this.closeSet = true;
        validateWindow();
        return this;
    }

    /** Only vehicles having every listed skill (see {@link VehicleConfig#skills(String...)}) may serve this stop. */
    public StopConfig requires(String... skills) {
        for (String s : skills) Validate.nonBlank(s, "required skill");
        this.requiredSkills.addAll(Arrays.asList(skills));
        return this;
    }

    /**
     * Makes this stop optional: the solver may leave it unserved at this cost, which overrides
     * the model-wide {@link RoutingBuilder#allowDroppingStops(long)} penalty for this stop only.
     *
     * @throws ModelException if {@code penalty} is not positive
     */
    public StopConfig penaltyIfDropped(long penalty) {
        Validate.positive(penalty, "penaltyIfDropped");
        this.dropPenalty = penalty;
        return this;
    }

    /** Rejects non-finite or out-of-range WGS84 coordinates. */
    static void validateCoordinates(double lat, double lon, String owner) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new ModelException(owner + ": latitude must be within [-90, 90], got: " + lat);
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new ModelException(owner + ": longitude must be within [-180, 180], got: " + lon);
        }
    }

    private void validateWindow() {
        // When both bounds are explicitly set, the window must be strictly positive.
        if (openSet && closeSet && openMin >= closeMin) {
            throw new ModelException(
                    "Time window is invalid: openFrom must be strictly before closeAt");
        }
    }
}

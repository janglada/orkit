package opt.routing;

import opt.ModelException;

import java.time.Duration;
import java.time.LocalTime;

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

    StopConfig() {}

    /** Sets the geographic location of this stop (required when using haversine distances). */
    public StopConfig at(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        this.atSet = true;
        return this;
    }

    /** Sets the demand picked up or delivered at this stop, in the same units as vehicle capacity. */
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
        this.openMin = time.toSecondOfDay() / 60;
        this.openSet = true;
        validateWindow();
        return this;
    }

    /** Sets the latest time-of-day the stop may be served; defaults to 23:59 (end of day). */
    public StopConfig closeAt(LocalTime time) {
        this.closeMin = time.toSecondOfDay() / 60;
        this.closeSet = true;
        validateWindow();
        return this;
    }

    private void validateWindow() {
        // When both bounds are explicitly set, the window must be strictly positive.
        if (openSet && closeSet && openMin >= closeMin) {
            throw new ModelException(
                    "Time window is invalid: openFrom must be strictly before closeAt");
        }
    }
}

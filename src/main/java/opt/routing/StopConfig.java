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

    StopConfig() {}

    public StopConfig at(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        return this;
    }

    public StopConfig demand(int units) {
        if (units < 0) throw new ModelException("demand must be >= 0, got: " + units);
        this.demand = units;
        return this;
    }

    public StopConfig serviceTime(Duration duration) {
        if (duration.isNegative()) throw new ModelException("serviceTime must be non-negative");
        this.serviceTimeMin = (int) duration.toMinutes();
        return this;
    }

    public StopConfig openFrom(LocalTime time) {
        this.openMin = time.toSecondOfDay() / 60;
        validateWindow();
        return this;
    }

    public StopConfig closeAt(LocalTime time) {
        this.closeMin = time.toSecondOfDay() / 60;
        validateWindow();
        return this;
    }

    private void validateWindow() {
        if (openMin > 0 && closeMin < 24 * 60 && openMin >= closeMin) {
            throw new ModelException(
                    "Time window is invalid: openFrom must be strictly before closeAt");
        }
    }
}

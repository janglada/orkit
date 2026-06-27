package opt.routing;

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
        this.demand = units;
        return this;
    }

    public StopConfig serviceTime(Duration duration) {
        this.serviceTimeMin = (int) duration.toMinutes();
        return this;
    }

    public StopConfig openFrom(LocalTime time) {
        this.openMin = time.toSecondOfDay() / 60;
        return this;
    }

    public StopConfig closeAt(LocalTime time) {
        this.closeMin = time.toSecondOfDay() / 60;
        return this;
    }
}

package opt.routing.internal;

import opt.routing.DistanceSource;
import opt.routing.PickupDeliveryPolicy;
import opt.routing.RoutingGoal;

import java.util.List;

/**
 * Immutable snapshot of a routing model as declared through {@code RoutingBuilder}.
 *
 * @param depots          depots in declaration order; the first is the default
 * @param vehicles        vehicles in declaration order
 * @param stops           stops in declaration order
 * @param distanceSource  how inter-node distances (and optionally travel times) are sourced
 * @param droppingPenalty per-stop penalty when dropping is allowed; {@code <= 0} disables dropping
 * @param goal            optimization goal
 * @param travelSpeedKmh  speed used to derive travel times when the source has no time matrix
 * @param pairs           pickup-and-delivery pairs
 * @param pickupDeliveryPolicy ordering policy among pairs on one vehicle
 */
public record RoutingSpec(
        List<DepotDef> depots,
        List<VehicleDef> vehicles,
        List<StopDef> stops,
        DistanceSource distanceSource,
        long droppingPenalty,
        RoutingGoal goal,
        double travelSpeedKmh,
        List<PickupDeliveryDef> pairs,
        PickupDeliveryPolicy pickupDeliveryPolicy
) {
    public RoutingSpec {
        depots = List.copyOf(depots);
        vehicles = List.copyOf(vehicles);
        stops = List.copyOf(stops);
        pairs = List.copyOf(pairs);
    }
}

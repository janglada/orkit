package io.github.janglada.orkit.routing;

/** Factory for routing objectives. */
public final class Goals {

    /**
     * Returns the goal of minimizing the total distance traveled across all vehicles.
     * {@link RoutingPlan#objective()} then reports total meters.
     */
    public static RoutingGoal totalDistance() {
        return new RoutingGoal.MinimizeTotalDistance();
    }

    /**
     * Returns the goal of minimizing total cost: each vehicle's distance times its
     * {@link VehicleConfig#costPerMeter(long) cost per meter} (default 1), its route duration
     * times its {@link VehicleConfig#costPerMinute(long) cost per minute} (default 0), the
     * {@link VehicleConfig#fixedCost(long)} of every vehicle used, and lateness penalties from
     * {@link StopConfig#lateAfter(java.time.LocalTime, long)}. {@link RoutingPlan#objective()}
     * then reports that sum.
     */
    public static RoutingGoal totalCost() {
        return new RoutingGoal.MinimizeTotalCost();
    }

    /**
     * Returns the goal of minimizing the number of vehicles that serve at least one stop,
     * with total distance as a tiebreaker. {@link RoutingPlan#objective()} then reports the
     * vehicle count.
     * <p>
     * Serving stops comes first: a droppable stop is left out only when no vehicle can serve
     * it, never to save a vehicle, whatever its penalty. Among stops that must be dropped, the
     * penalties still decide which ones.
     */
    public static RoutingGoal vehiclesUsed() {
        return new RoutingGoal.MinimizeVehiclesUsed();
    }

    private Goals() {}
}

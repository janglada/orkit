package opt.routing;

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
     * Returns the goal of minimizing total distance plus the {@link VehicleConfig#fixedCost(long)}
     * of every vehicle used. Fixed costs are in the same units as meters.
     * {@link RoutingPlan#objective()} then reports meters plus fixed costs.
     */
    public static RoutingGoal totalCost() {
        return new RoutingGoal.MinimizeTotalCost();
    }

    /**
     * Returns the goal of minimizing the number of vehicles that serve at least one stop,
     * with total distance as a tiebreaker. {@link RoutingPlan#objective()} then reports the
     * vehicle count.
     */
    public static RoutingGoal vehiclesUsed() {
        return new RoutingGoal.MinimizeVehiclesUsed();
    }

    private Goals() {}
}

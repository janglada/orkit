package opt.routing;

/** Factory for routing objectives. */
public final class Goals {

    /** Returns the goal of minimizing the total distance traveled across all vehicles. */
    public static RoutingGoal totalDistance() {
        return new RoutingGoal.MinimizeTotalDistance();
    }

    private Goals() {}
}

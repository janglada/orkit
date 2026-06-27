package opt.routing;

/** Factory for routing objectives. */
public final class Goals {

    public static RoutingGoal totalDistance() {
        return new RoutingGoal.MinimizeTotalDistance();
    }

    private Goals() {}
}

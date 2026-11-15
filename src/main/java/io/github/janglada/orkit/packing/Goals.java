package io.github.janglada.orkit.packing;

/** Factory for container-loading objectives. */
public final class Goals {

    /** Returns the goal of minimizing the number of containers that receive at least one box. */
    public static PackingGoal minimizeContainersUsed() {
        return new PackingGoal.MinimizeContainersUsed();
    }

    /** Returns the goal of maximizing the total packed volume of boxes across all containers. */
    public static PackingGoal maximizeVolumeUtilization() {
        return new PackingGoal.MaximizeVolumeUtilization();
    }

    private Goals() {}
}

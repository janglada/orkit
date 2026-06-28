package opt.packing;

/** Objective for a container-loading problem. Create instances via {@link Goals}. */
public sealed interface PackingGoal
        permits PackingGoal.MinimizeContainersUsed, PackingGoal.MaximizeVolumeUtilization {

    /** Minimize the number of containers that receive at least one box. */
    record MinimizeContainersUsed() implements PackingGoal {}

    /** Maximize the total packed volume of boxes across all containers. */
    record MaximizeVolumeUtilization() implements PackingGoal {}
}

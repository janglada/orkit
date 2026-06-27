package opt.routing;

/** First-solution strategy for vehicle routing. Maps to OR-Tools {@code FirstSolutionStrategy}. */
public enum Strategy {
    AUTOMATIC,
    PATH_CHEAPEST_ARC,
    PATH_MOST_CONSTRAINED_ARC,
    SAVINGS,
    SWEEP,
    CHRISTOFIDES,
    ALL_UNPERFORMED,
    BEST_INSERTION,
    PARALLEL_CHEAPEST_INSERTION,
    SEQUENTIAL_CHEAPEST_INSERTION,
    LOCAL_CHEAPEST_INSERTION,
    GLOBAL_CHEAPEST_ARC,
    LOCAL_CHEAPEST_ARC,
    FIRST_UNBOUND_MIN_VALUE
}

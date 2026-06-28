package opt.routing;

/** First-solution strategy for vehicle routing. Maps to OR-Tools {@code FirstSolutionStrategy}. */
public enum Strategy {
    /** Lets OR-Tools pick the first-solution strategy based on the model. */
    AUTOMATIC,
    /** Extends each route by repeatedly appending the cheapest outgoing arc. */
    PATH_CHEAPEST_ARC,
    /** Like {@link #PATH_CHEAPEST_ARC} but prioritizes the most constrained node next. */
    PATH_MOST_CONSTRAINED_ARC,
    /** Clarke-Wright savings heuristic: merges routes to maximize savings. */
    SAVINGS,
    /** Sweeps nodes by polar angle around the depot to form clusters, then routes within each. */
    SWEEP,
    /** Christofides' approximation, geared toward metric TSP-style instances. */
    CHRISTOFIDES,
    /** Starts with no stops served, then adds them during local search only. */
    ALL_UNPERFORMED,
    /** Inserts each unserved stop at the position that least increases cost. */
    BEST_INSERTION,
    /** Parallel variant of {@link #BEST_INSERTION} evaluating all insertions concurrently. */
    PARALLEL_CHEAPEST_INSERTION,
    /** Sequential variant of {@link #BEST_INSERTION} processing stops one at a time. */
    SEQUENTIAL_CHEAPEST_INSERTION,
    /** Inserts each stop at the cheapest local position as it is considered. */
    LOCAL_CHEAPEST_INSERTION,
    /** Greedily connects the two nodes whose arc is globally cheapest at each step. */
    GLOBAL_CHEAPEST_ARC,
    /** Connects the cheapest outgoing arc of the most recently extended node. */
    LOCAL_CHEAPEST_ARC,
    /** Assigns the smallest still-unbound value; rarely useful for routing. */
    FIRST_UNBOUND_MIN_VALUE
}

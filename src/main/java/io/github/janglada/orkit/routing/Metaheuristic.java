package io.github.janglada.orkit.routing;

/** Local-search metaheuristic for vehicle routing. Maps to OR-Tools {@code LocalSearchMetaheuristic}. */
public enum Metaheuristic {
    /** Lets OR-Tools choose the metaheuristic based on the model. */
    AUTOMATIC,
    /** Accepts the first improving move found; cheapest but prone to local optima. */
    GREEDY_DESCENT,
    /** Penalizes cost of frequently used arcs to escape local optima; often the best default. */
    GUIDED_LOCAL_SEARCH,
    /** Accepts worse moves probabilistically, annealing toward a minimum over time. */
    SIMULATED_ANNEALING,
    /** Forbids recently reversed moves to avoid cycling. */
    TABU_SEARCH,
    /** OR-Tools' generic tabu variant operating on assignment variables. */
    GENERIC_TABU_SEARCH
}

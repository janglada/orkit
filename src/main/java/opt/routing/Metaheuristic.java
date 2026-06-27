package opt.routing;

/** Local-search metaheuristic for vehicle routing. Maps to OR-Tools {@code LocalSearchMetaheuristic}. */
public enum Metaheuristic {
    AUTOMATIC,
    GREEDY_DESCENT,
    GUIDED_LOCAL_SEARCH,
    SIMULATED_ANNEALING,
    TABU_SEARCH,
    GENERIC_TABU_SEARCH
}

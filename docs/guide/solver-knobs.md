# Solver knobs

`solver()` returns a fresh solver each call. Configure it, then call `solve()`.

## Every solver

| Method | Default | Notes |
|---|---|---|
| `timeLimit(Duration)` | 1 minute (CP-SAT), 30 seconds (routing) | Must be positive; null, zero and negative limits raise `ModelException`. |
| `verbose(boolean)` | `false` | Prints the solver's search log to standard output. |
| `solve()` | | Runs the search and returns the result. |

## CP-SAT domains

Job-shop, scheduling, knapsack, packing, booking and cutting return a `CpSatSolver<S>`.

| Method | Default | Notes |
|---|---|---|
| `workers(int)` | 8, capped at available processors | Parallel search workers; at least 1. |
| `seed(int)` | 0 | Runs are reproducible for a seed only with `workers(1)`. |
| `onSolution(Consumer<S>)` | none | Called with every improving intermediate result (status `FEASIBLE`). |
| `warmStart(S previous)` | none | Hints the search with an earlier feasible result. |
| `cancel()` | | Stops a running solve; `solve()` returns the best result so far. |
| `exportModel(Path)` | none | Writes the CP-SAT model before each search. |
| `explainInfeasibility(boolean)` | `false` | Names conflicting constraint groups on infeasible models. See [Diagnostics](diagnostics.md). |

### Intermediate solutions

The listener receives a fully built result for every improving solution. It runs on the solver
thread, so keep it short.

```java
List<JobShopSchedule> progress = new ArrayList<>();
JobShopSchedule best = problem.solver()
        .timeLimit(Duration.ofMinutes(5))
        .onSolution(progress::add)
        .solve();
```

A listener exception stops the search and is rethrown from `solve()` wrapped in a
`ModelException`; an `Error` is rethrown as is.

### Warm starts

Pass an earlier feasible result to give the search a head start, for example after editing
the model. Ids that no longer exist are ignored.

```java
var first = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
var better = problem.solver().timeLimit(Duration.ofMinutes(5)).warmStart(first).solve();
```

### Cancellation

`cancel()` is safe from any thread and from a listener. A cancel issued while the model is
still being built is honored as soon as the search starts.

```java
CpSatSolver<JobShopSchedule> solver = problem.solver().timeLimit(Duration.ofMinutes(5));
solver.onSolution(s -> { if (s.makespan() < 100) solver.cancel(); });
JobShopSchedule good = solver.solve();
```

### Exporting the model

```java
problem.solver().exportModel(Path.of("model.txt")).solve();   // text protobuf
problem.solver().exportModel(Path.of("model.pb")).solve();    // binary protobuf
```

The model is validated first. Names ending in `.txt` or `.textproto` produce text format,
anything else binary. The file can be loaded by any CP-SAT tool, which helps when reporting
a solver issue or tuning parameters outside Java.

## Routing

`RoutingBuilder.solver()` returns a `RoutingSolver`. OR-Tools routing search has no worker
count or seed, so those knobs are deliberately absent.

| Method | Default | Notes |
|---|---|---|
| `firstSolution(Strategy)` | `AUTOMATIC` | How the initial routes are built: `PATH_CHEAPEST_ARC`, `SAVINGS`, `CHRISTOFIDES`, `PARALLEL_CHEAPEST_INSERTION`, ... |
| `improveWith(Metaheuristic)` | `AUTOMATIC` | Local search after the first solution: `GREEDY_DESCENT`, `GUIDED_LOCAL_SEARCH`, `SIMULATED_ANNEALING`, `TABU_SEARCH`, `GENERIC_TABU_SEARCH`. |
| `onSolution(Consumer<RoutingPlan>)` | none | Called for every solution the search accepts. Arrival times on intermediate plans are the earliest consistent with the routes. |
| `warmStart(RoutingPlan)` | none | Starts from earlier routes. Stale ids are ignored; routes that are infeasible for the current model fall back to a regular start. |
| `cancel()` | | Keeps the best routes so far, or reports `UNKNOWN` if none was found yet. |

!!! tip "Metaheuristics need time"
    With `AUTOMATIC` or `GREEDY_DESCENT` the search stops at the first local optimum, often in
    well under a second. `GUIDED_LOCAL_SEARCH` keeps improving until the time limit, so pair
    it with a limit you are willing to wait for.

## Threading

A solver runs one solve at a time; a second concurrent `solve()` on the same solver throws
`ModelException`. Problems are immutable, so use one solver per thread:

```java
var problem = builder.build();
List<CompletableFuture<JobShopSchedule>> runs = IntStream.range(0, 4)
        .mapToObj(seed -> CompletableFuture.supplyAsync(
                () -> problem.solver().workers(1).seed(seed).solve()))
        .toList();
```

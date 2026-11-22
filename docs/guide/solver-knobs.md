# Solver settings

A *solver* runs one search. You get one with `solver()`, adjust its settings, and call
`solve()`:

```java
JobShopSchedule schedule = problem.solver()
        .timeLimit(Duration.ofSeconds(30))
        .workers(4)
        .solve();
```

Every call to `solver()` returns a new solver with default settings. Settings never leak from
one solve to the next.

**In a hurry?** The only setting most programs need is `timeLimit`.

## Settings every solver has

| Method | Default | What it does |
|---|---|---|
| `timeLimit(Duration)` | 1 minute (30 seconds for routing) | The longest the search may run. |
| `verbose(boolean)` | `false` | Prints the solver's own progress log. |
| `solve()` | | Runs the search and returns the result. |

### Time limit

The search stops as soon as it has proven the best plan, or when the time is up, whichever
comes first. Small problems usually finish in milliseconds, whatever the limit.

```java
problem.solver().timeLimit(Duration.ofSeconds(5)).solve();    // a quick answer
problem.solver().timeLimit(Duration.ofMinutes(10)).solve();   // a thorough answer
```

When the time runs out, you get the best plan found so far with status `FEASIBLE`, or
`UNKNOWN` if no plan was found at all.

The limit must be positive. Zero, a negative duration or `null` raises a `ModelException`.

### Progress log

```java
problem.solver().verbose(true).solve();
```

This prints the underlying solver's log to the console: how it simplified the model, each
better plan it found, and how the proven limit moved. It is useful when a solve is slower than
you expect and you want to see what is going on.

## Most domains: the CP-SAT solver

Job-shop, scheduling, knapsack, packing, booking and cutting all run on the same engine,
called CP-SAT. Their solver type is `CpSatSolver<S>`, and it adds these settings:

| Method | Default | What it does |
|---|---|---|
| `workers(int)` | 8, or fewer if the machine has fewer CPU cores | How many threads search in parallel. |
| `seed(int)` | 0 | Starting point for random choices. |
| `onSolution(Consumer<S>)` | none | Calls your code each time a better plan is found. |
| `warmStart(S previous)` | none | Starts the search from a plan you already have. |
| `cancel()` | | Stops a running search from outside. |
| `exportModel(Path)` | none | Saves the mathematical model to a file. |
| `explainInfeasibility(boolean)` | `false` | Names the rules that clash when no plan exists. See [Diagnostics](diagnostics.md#finding-out-why-there-is-no-plan). |

### Workers

More workers usually means a faster search, because each one tries a different strategy and
they share what they learn.

```java
problem.solver().workers(1).solve();    // one thread: slower, but repeatable
problem.solver().workers(16).solve();   // sixteen threads, on a big server
```

Use fewer workers when the machine is shared with other work.

### Seed and repeatable runs

With several workers, two runs of the same problem can return different plans of the same
quality, because the threads race each other. If you need the **exact same plan every time**
(for tests, for example), use one worker and a fixed seed:

```java
JobShopSchedule a = problem.solver().workers(1).seed(42).solve();
JobShopSchedule b = problem.solver().workers(1).seed(42).solve();
// a and b are the same plan
```

### Watching progress

`onSolution` calls your code every time the solver finds a better plan. Each call receives a
complete result object, just like the final one, with status `FEASIBLE`.

```java
JobShopSchedule best = problem.solver()
        .timeLimit(Duration.ofSeconds(10))
        .onSolution(s -> System.out.printf("found makespan %d after %d ms%n",
                s.makespan(), s.runtime().toMillis()))
        .solve();

System.out.println("final: " + best.status() + " " + best.makespan());
```

On a problem with 12 jobs and 5 machines this prints something like:

```text
found makespan 339 after 7 ms
found makespan 326 after 9 ms
found makespan 319 after 11 ms
...
found makespan 289 after 39 ms
found makespan 287 after 42 ms
final: OPTIMAL 287
```

Good uses: show a progress bar, draw the current plan on screen, or log how quality improves
over time.

Two things to know:

- Your code runs on the solver's own thread, so keep it short. Slow code slows the search.
- If your code throws an exception, the search stops and `solve()` throws a
  `ModelException` that wraps it. (A Java `Error`, such as `OutOfMemoryError`, is passed on
  unchanged.)

### Continuing from an earlier plan

A *warm start* gives the solver a plan to begin from, so it does not start from scratch.

```java
JobShopSchedule first  = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
JobShopSchedule better = problem.solver().timeLimit(Duration.ofMinutes(5)).warmStart(first).solve();
```

Typical uses:

- **Quick answer now, better answer later.** Show the user a plan after 10 seconds, then
  keep improving in the background.
- **Small changes.** Yesterday's plan is a good starting point for today's problem, even if
  a few jobs were added or removed. Names that no longer exist are simply ignored.

The earlier plan is only a hint. The solver is free to move away from it.

### Stopping early

`cancel()` stops a running search. `solve()` then returns the best plan found so far. You can
call it from any thread, including from inside `onSolution`. If you call it while the solver
is still preparing the model, the search stops the moment it starts.

**Example: a "Stop" button or a timer.**

```java
CpSatSolver<JobShopSchedule> solver = problem.solver().timeLimit(Duration.ofMinutes(5));

ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
timer.schedule(solver::cancel, 2, TimeUnit.SECONDS);   // or call cancel() when the user clicks "Stop"

JobShopSchedule result = solver.solve();               // returns the best plan found so far
timer.shutdown();
```

**Example: stop as soon as the plan is good enough.**

```java
CpSatSolver<JobShopSchedule> solver = problem.solver().timeLimit(Duration.ofMinutes(5));
solver.onSolution(s -> { if (s.makespan() < 400) solver.cancel(); });

JobShopSchedule goodEnough = solver.solve();           // FEASIBLE, makespan below 400
```

### Saving the model to a file

```java
problem.solver().exportModel(Path.of("model.txt")).solve();   // readable text
problem.solver().exportModel(Path.of("model.pb")).solve();    // compact binary
```

This writes the mathematical model that orkit built from your description. File names ending
in `.txt` or `.textproto` give readable text, anything else gives the compact binary format.
The text version starts like this:

```text
variables { name: "s_J1_0" domain: [0, 50] }
variables { name: "e_J1_0" domain: [0, 60] }
...
```

You rarely need this. It helps when you report a solver problem, or when an OR-Tools expert
wants to study the model with other tools.

## Vehicle routing: the routing solver

Routing uses a different engine, so `Optimize.vehicleRouting()...solver()` returns a
`RoutingSolver`. It works in two phases: it first builds a rough set of routes, then keeps
improving them with small changes. You can choose the method for each phase.

| Method | Default | What it does |
|---|---|---|
| `firstSolution(Strategy)` | `AUTOMATIC` | How the first routes are built. |
| `improveWith(Metaheuristic)` | `AUTOMATIC` | How the routes are improved afterwards. |
| `onSolution(Consumer<RoutingPlan>)` | none | Calls your code for each plan the search accepts. |
| `warmStart(RoutingPlan)` | none | Starts from routes you already have. |
| `cancel()` | | Stops the search and keeps the best routes so far. |

There is no `workers` or `seed` here: the routing engine does not offer them.

```java
RoutingPlan plan = problem.solver()
        .timeLimit(Duration.ofSeconds(30))
        .firstSolution(Strategy.PARALLEL_CHEAPEST_INSERTION)
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .solve();
```

### Choosing the methods

If you are unsure, this combination works well for most delivery problems:

```java
.firstSolution(Strategy.SAVINGS)
.improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
```

| First-solution strategy | In plain words |
|---|---|
| `AUTOMATIC` | Let the engine choose. |
| `PATH_CHEAPEST_ARC` | Always drive to the nearest unvisited stop. Fast and simple. |
| `SAVINGS` | Start with one trip per stop, then merge trips where that saves the most distance. |
| `PARALLEL_CHEAPEST_INSERTION` | Grow all routes together, inserting each stop where it costs least. Good with time windows. |
| `CHRISTOFIDES` | A classic method that gives short tours when there are few restrictions. |

Other strategies exist; the [Javadoc](../api.md) lists them all.

| Improvement method | In plain words |
|---|---|
| `AUTOMATIC`, `GREEDY_DESCENT` | Keep making small changes while they help, and stop at the first plan that small changes cannot improve. Very fast. |
| `GUIDED_LOCAL_SEARCH` | When stuck, deliberately try different directions to escape. Usually gives the best routes. |
| `SIMULATED_ANNEALING`, `TABU_SEARCH`, `GENERIC_TABU_SEARCH` | Other ways to escape when stuck. |

!!! tip "Guided local search uses all the time you give it"
    With `AUTOMATIC` or `GREEDY_DESCENT` the search often stops in well under a second.
    `GUIDED_LOCAL_SEARCH` never stops on its own: it keeps trying until the time limit. Pick a
    limit you are willing to wait for, such as 10 to 60 seconds.

### Watching routing progress

Unlike CP-SAT, the routing search also passes **worse** plans to `onSolution`: trying a worse
plan is how it escapes when it is stuck. Keep track of the best one yourself:

```java
AtomicLong best = new AtomicLong(Long.MAX_VALUE);

RoutingPlan plan = problem.solver()
        .timeLimit(Duration.ofSeconds(3))
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .onSolution(p -> {
            long meters = p.totalDistanceMeters();
            if (meters < best.get()) {            // the search also visits worse plans
                best.set(meters);
                System.out.println("new best: " + meters + " m");
            }
        })
        .solve();
```

On a problem with 25 stops this prints something like:

```text
new best: 73554 m
new best: 73215 m
...
new best: 60117 m
new best: 59309 m
```

The plan returned by `solve()` is always the best one found.

### Continuing from earlier routes

```java
RoutingPlan quick  = problem.solver().timeLimit(Duration.ofSeconds(2)).solve();
RoutingPlan better = problem.solver()
        .timeLimit(Duration.ofSeconds(30))
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .warmStart(quick)
        .solve();
```

Stops and vehicles that no longer exist are ignored. If the old routes break a rule of the
current problem, the solver quietly starts from scratch instead.

### Stopping a routing search

`cancel()` works as it does for CP-SAT. You get the best routes found so far, or status
`UNKNOWN` if none were found yet.

## Using several threads

Two rules:

1. **A problem can be shared.** It never changes, so any number of threads may use it.
2. **A solver cannot.** One solver runs one search at a time. Give each thread its own.

```java
CpSatProblem<JobShopSchedule> problem = builder.build();

// Four searches at once, each with its own solver and its own seed.
List<CompletableFuture<JobShopSchedule>> runs = IntStream.range(0, 4)
        .mapToObj(seed -> CompletableFuture.supplyAsync(
                () -> problem.solver().workers(1).seed(seed).solve()))
        .toList();

// Keep the best of the four.
JobShopSchedule best = runs.stream()
        .map(CompletableFuture::join)
        .min(Comparator.comparingLong(JobShopSchedule::makespan))
        .orElseThrow();
```

Calling `solve()` on a solver that is already solving throws:

```text
ModelException: solve() is already running on this solver; create a new solver per concurrent solve.
```

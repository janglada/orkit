# Core concepts

All seven domains work the same way. Once you understand the few ideas on this page, every
domain page will look familiar.

## The big picture

You go through four stages, always in this order:

```text
Optimize  →  builder  →  problem  →  solver  →  solution
           describe     freeze      search      answer
```

| Stage | What it is | Example |
|---|---|---|
| **Builder** | Where you describe the problem, piece by piece. | `Optimize.jobShop().machines(...).job(...)` |
| **Problem** | A frozen copy of that description. It never changes again. | `builder.build()` |
| **Solver** | One search run, with its own settings such as the time limit. | `problem.solver().timeLimit(...)` |
| **Solution** | The answer: a status, a score, and the plan itself. | `solver.solve()` |

Most of the time you chain all four in one expression:

```java
JobShopSchedule schedule = Optimize.jobShop()      // builder
        .machines("MILL", "LATHE")
        .job("J1", j -> j.operation().on("MILL").takes(10))
        .solver()                                  // problem + solver in one step
        .timeLimit(Duration.ofSeconds(10))
        .solve();                                  // solution
```

Calling `solver()` directly on a builder is a shortcut for `build().solver()`.

## Describing things: names and small blocks

Everything you declare has a **name** (an *id*) and a **small block of settings** (a lambda):

```java
Optimize.vehicleRouting()
        .depot("Hub", 41.35, 2.13)
        .vehicle("Van-1", v -> v.capacity(20).fixedCost(50_000))
        .stop("Cust-A", s -> s.at(41.39, 2.16).demand(4).serviceTime(Duration.ofMinutes(10)));
```

Read `v -> v.capacity(20)` as "for this vehicle, set the capacity to 20". The names are yours
to choose. You use them again when you read the answer: `plan.routeOf("Van-1")`.

Three rules apply everywhere:

**1. Names must be unique.** Two machines, two employees or two stops cannot share a name.

```java
Optimize.multipleKnapsack()
        .bin("T1", b -> b.capacity(10))
        .bin("T1", b -> b.capacity(20));    // ModelException: Duplicate bin id: 'T1'.
```

**2. Values are checked as soon as you set them.**

```java
Optimize.multipleKnapsack()
        .bin("T1", b -> b.capacity(-5));    // ModelException: capacity() must be >= 1, got: -5
```

**3. The order of declarations does not matter.** You may refer to something before you
declare it. Here the rule "Frame before Assembly" comes first, and the jobs and machines
follow:

```java
JobShopSchedule s = Optimize.jobShop()
        .precedence("Frame", "Assembly")     // both jobs are declared further down
        .job("Assembly", j -> j.operation().on("BENCH").takes(30))
        .job("Frame", j -> j.operation().on("WELDER").takes(20))
        .machines("BENCH", "WELDER")
        .solver().solve();                   // OPTIMAL, makespan 50
```

## Problems: build once, solve many times

`build()` takes a snapshot of the builder. After that:

- The problem never changes, even if you keep adding things to the builder.
- You can solve it as often as you like, each time with different settings.
- It is safe to share between threads.

```java
JobShopBuilder builder = Optimize.jobShop()
        .machines("MILL", "LATHE", "DRILL")
        .job("J1", j -> j
                .operation().on("MILL").takes(10)
                .then().on("LATHE").takes(20)
                .then().on("DRILL").takes(5))
        .job("J2", j -> j
                .operation().onAnyOf("LATHE", "MILL").takes(15)
                .then().on("DRILL").takes(10));

CpSatProblem<JobShopSchedule> problem = builder.build();

// Solve the same problem twice: a quick look first, then a longer run that continues from it.
JobShopSchedule quick  = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
JobShopSchedule longer = problem.solver().timeLimit(Duration.ofMinutes(2)).warmStart(quick).solve();
```

Adding a job to the builder afterwards does not touch the problem you already built:

```java
builder.job("J3", j -> j.operation().on("DRILL").takes(100));

problem.solver().solve().makespan();   // 40: the snapshot still has two jobs
builder.solver().solve().makespan();   // 115: a new snapshot with three jobs
```

## Solvers: how the search runs

A solver holds the settings for **one** search. Every call to `solver()` gives you a fresh
one.

```java
JobShopSchedule schedule = problem.solver()
        .timeLimit(Duration.ofSeconds(30))    // stop after 30 seconds at the latest
        .workers(4)                           // use 4 CPU threads
        .solve();
```

All solvers have `timeLimit`, `verbose` and `solve`. The rest depends on the engine behind
the domain:

| Domains | Solver type | Extra settings |
|---|---|---|
| Job-shop, scheduling, knapsack, packing, booking, cutting | `CpSatSolver<S>` | `workers`, `seed`, `onSolution`, `warmStart`, `cancel`, `exportModel`, `explainInfeasibility` |
| Vehicle routing | `RoutingSolver` | `firstSolution`, `improveWith`, `onSolution`, `warmStart`, `cancel` |

[Solver settings](solver-knobs.md) explains each one with an example.

## Goals: what "best" means

A *goal* tells the solver what to aim for: the shortest schedule, the lowest cost, the fewest
vehicles. Each domain has a `Goals` class with the choices, and you pick one with
`goal(...)`:

```java
import io.github.janglada.orkit.jobshop.Goals;

Optimize.jobShop()
        .goal(Goals.minimizeTardiness(1))     // care about lateness, not total length
        // ...
```

If you do not call `goal(...)`, each domain uses a sensible default:

| Domain | Default goal | Other goals |
|---|---|---|
| Job-shop | Finish everything as early as possible (`minimizeMakespan()`) | `minimizeTardiness(w)`, `minimizeMakespanAndTardiness(wm, wt)`, `minimizeWeightedCompletionTime()` |
| Scheduling | None: any roster that follows the rules | `totalLaborCost()`, plus preferences added with `prefer(...)` |
| Routing | Shortest total distance (`totalDistance()`) | `totalCost()`, `vehiclesUsed()` |
| Knapsack | Highest total profit (`maximizeProfit()`) | |
| Packing | Fewest containers (`minimizeContainersUsed()`) | `maximizeVolumeUtilization()` |
| Booking | Most appointments placed (`maximizeScheduledAppointments()`) | `minimizeUnscheduledPenalty()` |
| Cutting | Least material consumed (`minimizeWaste()`) | `minimizeStockUsed()`, `minimizeTotalCost()` |

!!! warning "Two domains in one file"
    Every domain has its own class called `Goals`. If a file imports two domains with `*`,
    the compiler reports `reference to Goals is ambiguous`. Import the one you need by name,
    or write the full name: `io.github.janglada.orkit.routing.Goals.totalCost()`.

## Solutions: reading the answer

### What every solution has

| Method | What it tells you |
|---|---|
| `status()` | How the search ended. See the table below. |
| `isFeasible()` | `true` if there is a plan you can use (`OPTIMAL` or `FEASIBLE`). |
| `isOptimal()` | `true` only if the plan is proven to be the best. |
| `objective()` | The score of the plan for the chosen goal. Empty if there is no plan or no goal. |
| `runtime()` | How long the search took. |
| `bestBound()`, `gap()` | How far the plan might still be from the best. See [Diagnostics](diagnostics.md). |
| `diagnostics()` | Search statistics and, on request, reasons for infeasibility. |

```java
JobShopSchedule schedule = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();

schedule.status();       // OPTIMAL
schedule.isFeasible();   // true
schedule.isOptimal();    // true
schedule.objective();    // OptionalDouble[40.0]
schedule.runtime();      // PT0.003S (3 milliseconds)
```

### Statuses

| `SolveStatus` | In plain words | What to do |
|---|---|---|
| `OPTIMAL` | A plan was found, and it is proven that none is better. | Use it. |
| `FEASIBLE` | A valid plan was found, but a better one may exist. | Use it, or run longer. Normal for routing. |
| `INFEASIBLE` | No plan can satisfy all the rules. | Relax a rule. [Ask which rules clash](diagnostics.md#finding-out-why-there-is-no-plan). |
| `UNKNOWN` | The search stopped before finding any plan. | Raise the time limit. |
| `UNBOUNDED` | The goal can improve forever. Rare in practice. | Check the model for a missing limit. |
| `MODEL_INVALID` | The solver rejected the model. Rare in practice. | Please report it. |

A typical way to handle the result:

```java
switch (schedule.status()) {
    case OPTIMAL    -> System.out.println("Best possible plan: " + schedule.makespan());
    case FEASIBLE   -> System.out.println("A valid plan, maybe not the best: " + schedule.makespan());
    case INFEASIBLE -> System.out.println("No plan can satisfy all the rules.");
    case UNKNOWN    -> System.out.println("Ran out of time before finding anything.");
    default         -> System.out.println("Unexpected: " + schedule.status());
}
```

!!! note "When there is no plan"
    With `INFEASIBLE` or `UNKNOWN` the solution object still exists, but it is empty: lists
    such as `operations()` or `routes()` are empty and `objective()` is empty. Check
    `isFeasible()` before you read the plan.

### The plan itself

Each domain adds its own methods to read the plan:

```java
schedule.timelineOf("DRILL");     // job-shop: what runs on the drill, in order
roster.staffOf("Night", date);    // scheduling: who works that night
plan.routeOf("Van-1");            // routing: that van's route
sol.binOf("Fridge");              // knapsack: where the fridge went
```

Looking something up by name never throws. If the name is unknown, or the thing was left
out of the plan, you get an empty result:

```java
sol.binOf("I1");                           // Optional[T1]
sol.binOf("no-such-item");                 // Optional.empty
sol.binOf("I1").orElse("not packed");      // "T1"

schedule.timelineOf("NO-SUCH-MACHINE");    // [] (an empty list)
```

## Warnings

A *warning* is something that looks odd but does not stop the solve. Read them with
`warnings()` on the built problem. Most models have none.

```java
CpSatProblem<JobShopSchedule> problem = Optimize.jobShop()
        .machines("MILL")
        .setupTime("ALU", "STEEL", 12)      // nobody uses the STEEL type below
        .job("J1", j -> j.operation().on("MILL").type("ALU").takes(10))
        .build();

problem.warnings().forEach(System.out::println);
```

Prints:

```text
Setup type 'STEEL' is declared in the setup matrix but not used by any operation or initial
state; it has no effect (matrices may be shared across models).
```

## Errors

When your description does not make sense, orkit throws a `ModelException`. The message
names the thing that is wrong. You never get a silent wrong answer.

```java
try {
    Optimize.jobShop()
            .machines("MILL")
            .job("J1", j -> j.operation().on("LATHE").takes(10))   // LATHE was never declared
            .solver().solve();
} catch (ModelException e) {
    System.out.println(e.getMessage());
}
```

Prints:

```text
Job 'J1' operation 0 references unknown machine 'LATHE'.
```

Typical causes:

| Cause | Example message |
|---|---|
| The same name used twice | `Duplicate bin id: 'T1'.` |
| An empty name | `bin id must be non-blank.` |
| A value out of range | `capacity() must be >= 1, got: -5` |
| A name that was never declared | `Job 'J1' operation 0 references unknown machine 'LATHE'.` |
| Rules that contradict each other | `'Alice' is both assigned to and forbidden from 'Day' on 2026-07-06.` |
| A time limit of zero or less | `timeLimit must be > 0, got: PT0S` |
| Numbers so large that totals would overflow | a message containing `overflows` |

Some checks run when you declare a thing, some when you call `build()`, and the rest when
`solve()` starts. All of them run before the search begins.

`ModelException` is an unchecked exception, so you do not have to catch it. Catch it when
the model comes from user input and you want to show the message.

## Summary

- **Describe** with a builder: names plus small blocks of settings.
- **Freeze** with `build()` if you want to solve more than once. Otherwise skip it.
- **Search** with `solver()...solve()`, and always set a time limit you can live with.
- **Read** `status()` first, then the plan.

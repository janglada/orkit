# Core concepts

## The pieces

`Optimize` → **builder** → `build()` → **problem** → `solver()` → **solver** → `solve()` → **solution**

| Piece | Type | Role |
|---|---|---|
| Entry point | `Optimize` | Static factories, one per domain: `jobShop()`, `scheduling()`, `vehicleRouting()`, `multipleKnapsack()`, `packing()`, `booking()`, `cuttingStock()`. |
| Builder | `JobShopBuilder`, `RoutingBuilder`, ... | Mutable, fluent description of the problem. |
| Problem | `OptimizationProblem<S>` / `CpSatProblem<S>` | Immutable snapshot returned by `build()`. Reusable and safe to share. |
| Solver | `Solver<S>` / `CpSatSolver<S>` / `RoutingSolver` | Search settings plus `solve()`. A fresh one per `solver()` call. |
| Solution | `Solution` subtype | Status, objective, runtime, diagnostics and the domain result. |

## Builders and configurators

Every entity is declared with a unique id and a lambda that receives a configurator:

```java
Optimize.vehicleRouting()
        .depot("Hub", 41.35, 2.13)
        .vehicle("Van-1", v -> v.capacity(20).fixedCost(50_000))
        .stop("Cust-A", s -> s.at(41.39, 2.16).demand(4).serviceTime(Duration.ofMinutes(10)));
```

- Ids must be non-blank and unique within their kind; duplicates raise `ModelException`.
- Configurators validate each argument as you set it (negative quantities, empty windows).
- Cross-references (a vehicle's depot, a precedence's job, a lock's shift) may be declared in
  any order and are resolved when the problem is built.

## Problems are immutable

`build()` takes a snapshot. Later changes to the builder do not affect it, and the problem can
be solved many times, from many threads, each with its own solver:

```java
CpSatProblem<JobShopSchedule> problem = builder.build();

JobShopSchedule quick = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
JobShopSchedule long_ = problem.solver().timeLimit(Duration.ofMinutes(2)).warmStart(quick).solve();
```

Calling `solver()` directly on a builder is a shortcut for `build().solver()`.

## Solvers

| Interface | Domains | Adds |
|---|---|---|
| `Solver<S>` | all | `timeLimit(Duration)`, `verbose(boolean)`, `solve()` |
| `CpSatSolver<S>` | job-shop, scheduling, knapsack, packing, booking, cutting | `workers`, `seed`, `onSolution`, `warmStart`, `cancel`, `exportModel`, `explainInfeasibility` |
| `RoutingSolver` | routing | `firstSolution`, `improveWith`, `onSolution`, `warmStart`, `cancel` |

See [Solver knobs](solver-knobs.md) for every setting.

## Goals

Each domain has a sealed goal type and a `Goals` factory. Set the objective with `goal(...)`;
every builder has a sensible default.

| Domain | Default goal | Other goals |
|---|---|---|
| Job-shop | `minimizeMakespan()` | `minimizeTardiness(w)`, `minimizeMakespanAndTardiness(wm, wt)`, `minimizeWeightedCompletionTime()` |
| Scheduling | none (pure feasibility) | `totalLaborCost()`, plus soft goals added with `prefer(...)` |
| Routing | `totalDistance()` | `totalCost()`, `vehiclesUsed()` |
| Knapsack | `maximizeProfit()` | |
| Packing | `minimizeContainersUsed()` | `maximizeVolumeUtilization()` |
| Booking | `maximizeScheduledAppointments()` | `minimizeUnscheduledPenalty()` |
| Cutting | `minimizeWaste()` | `minimizeStockUsed()`, `minimizeTotalCost()` |

## Results

Every result implements `Solution`:

| Method | Meaning |
|---|---|
| `status()` | A `SolveStatus` (see below). |
| `objective()` | `OptionalDouble`; empty when no solution was found or the model has no objective. |
| `runtime()` | Wall-clock `Duration` of the solve. |
| `isFeasible()` | `true` for `OPTIMAL` or `FEASIBLE`. |
| `isOptimal()` | `true` only when optimality was proven. |
| `diagnostics()` | Bound, branches, conflicts, infeasibility reasons. See [Diagnostics](diagnostics.md). |
| `bestBound()`, `gap()` | Shortcuts into the diagnostics. |

Domain results add their own accessors, for example `JobShopSchedule.timelineOf(machine)`,
`RoutingPlan.routeOf(vehicle)` or `PackingSolution.containerOf(box)`. Lookups by id return
`Optional` and are empty for unknown ids.

### Statuses

| `SolveStatus` | Meaning |
|---|---|
| `OPTIMAL` | A solution was found and proven optimal. |
| `FEASIBLE` | A solution was found, without an optimality proof (normal for routing, or when the time limit stops the search). |
| `INFEASIBLE` | No solution exists. For routing: the search space was exhausted without a solution. |
| `UNBOUNDED` | The objective can improve without limit. |
| `UNKNOWN` | The search stopped (time limit, cancel) before reaching a conclusion. Raise the time limit. |
| `MODEL_INVALID` | The underlying model was rejected by the solver. |

When no solution is available, result lists are empty and `objective()` is empty.

## Warnings

`OptimizationProblem.warnings()` returns non-fatal diagnostics collected while building, such
as a job-shop setup type declared in a matrix but used by no operation. It is empty for most
models.

## Errors

`ModelException` (unchecked) signals an inconsistent model: duplicate or unknown ids, invalid
numbers, contradictory options, inputs whose derived totals would overflow 64-bit integers, or
a second concurrent `solve()` on the same solver. The message names the offending entity.

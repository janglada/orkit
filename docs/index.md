---
hide:
  - navigation
---

# ![orkit](assets/logo.svg){ width="288" } { #orkit }

**A fluent Java 21+ API for operations research on top of Google OR-Tools 9.15.**

orkit lets you describe an optimization problem the way you would explain it to a colleague
(machines and jobs, nurses and shifts, vans and stops) and get back a typed, immutable result.
It hides CP-SAT and the OR-Tools routing library behind seven small builders, so you never
import `com.google.ortools.*`.

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE", "DRILL")
        .job("J1", j -> j
                .operation().on("MILL").takes(10)
                .then().on("LATHE").takes(20)
                .then().on("DRILL").takes(5))
        .job("J2", j -> j
                .operation().onAnyOf("LATHE", "MILL").takes(15)
                .then().on("DRILL").takes(10))
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

System.out.println(schedule.status() + " makespan=" + schedule.makespan());
```

## Seven domains

<div class="grid cards" markdown>

-   :material-factory:{ .lg .middle } **[Job-shop scheduling](domains/jobshop.md)**

    ---

    Flexible machines, setup times, downtime, time windows, precedences between jobs,
    parallel machines and calendar time.

-   :material-account-clock:{ .lg .middle } **[Shift scheduling](domains/scheduling.md)**

    ---

    Recurring shifts, skills and role mixes, rest rules, weekly hour limits, contracts,
    preferences and soft staffing.

-   :material-truck-delivery:{ .lg .middle } **[Vehicle routing](domains/routing.md)**

    ---

    Multiple depots, capacities in several units, time windows, soft deadlines, breaks,
    pickup and delivery, skills and per-vehicle costs.

-   :material-bag-personal:{ .lg .middle } **[Multiple knapsack](domains/knapsack.md)**

    ---

    Assign items to capacity-limited bins to maximize total profit.

-   :material-package-variant-closed:{ .lg .middle } **[Container packing](domains/packing.md)**

    ---

    3D bin packing with rotation, physical support, stacking order and weight-bearing limits.

-   :material-calendar-check:{ .lg .middle } **[Resource booking](domains/booking.md)**

    ---

    Appointments on doctors, rooms and staff with availability windows, buffers and
    multi-resource requirements.

-   :material-content-cut:{ .lg .middle } **[Cutting stock](domains/cutting.md)**

    ---

    1D rolls and 2D sheets, rotation, blade width and guillotine layouts.

</div>

## What you get

- **One shape for every problem.** Ids plus lambda configurators, a `Goals` factory per
  domain, `build()` for an immutable reusable problem, `solver()` for a configurable solve.
- **Typed results.** Records and `Optional` lookups instead of raw variable values.
- **Honest statuses.** `OPTIMAL`, `FEASIBLE`, `INFEASIBLE`, `UNKNOWN` mean what they say,
  including for heuristic routing search.
- **Diagnostics.** Best proven bound, optimality gap and, on request, the names of the
  constraints that make a model infeasible.
- **Solver control.** Time limits, worker counts, seeds, intermediate-solution callbacks,
  warm starts, cancellation and model export.
- **Fail-fast validation.** Inconsistent input raises a `ModelException` that names the
  offending id, before any solving starts.

## Next steps

- [Getting started](guide/getting-started.md): install, requirements, first solve.
- [Core concepts](guide/concepts.md): builders, problems, solvers and results.
- [Examples](examples.md): seven runnable programs, one per domain.

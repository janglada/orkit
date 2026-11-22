---
hide:
  - navigation
---

# ![orkit](assets/logo.svg){ width="288" } { #orkit }

**Describe a planning problem in plain Java. Get the best plan back.**

orkit is a Java library (Java 21 or newer) for planning and scheduling problems: which job
runs on which machine, who works which shift, which van visits which customer, what fits in
which container.

You describe the problem with the words you already use: machines and jobs, nurses and
shifts, vans and stops. orkit hands it to [Google OR-Tools](https://developers.google.com/optimization),
a well-known open-source solver, and gives you back a plain Java object with the answer. You
never write solver code yourself.

## A first look

Two jobs must go through three machines. Each step takes some time, and a machine can do only
one thing at a time. What is the fastest way to finish both jobs?

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE", "DRILL")
        .job("J1", j -> j
                .operation().on("MILL").takes(10)     // first 10 minutes on the mill
                .then().on("LATHE").takes(20)         // then 20 on the lathe
                .then().on("DRILL").takes(5))         // then 5 on the drill
        .job("J2", j -> j
                .operation().onAnyOf("LATHE", "MILL").takes(15)   // either machine will do
                .then().on("DRILL").takes(10))
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

System.out.println(schedule.status() + " makespan=" + schedule.makespan());
```

Prints:

```text
OPTIMAL makespan=40
```

`OPTIMAL` means the solver proved that no plan can finish sooner. The *makespan* is the time
at which the last step ends: both jobs are done after 40 minutes.

## What can it solve?

<div class="grid cards" markdown>

-   :material-factory:{ .lg .middle } **[Job-shop scheduling](domains/jobshop.md)**

    ---

    *Which job runs on which machine, and when?* Handles machine choices, setup times,
    maintenance, due dates and real calendar times.

-   :material-account-clock:{ .lg .middle } **[Shift scheduling](domains/scheduling.md)**

    ---

    *Who works which shift?* Handles skills, days off, rest rules, weekly hours, preferences
    and cost.

-   :material-truck-delivery:{ .lg .middle } **[Vehicle routing](domains/routing.md)**

    ---

    *Which vehicle visits which stop, in what order?* Handles capacity, opening hours, driver
    breaks, pickups and deliveries.

-   :material-bag-personal:{ .lg .middle } **[Multiple knapsack](domains/knapsack.md)**

    ---

    *Which items go in which bin?* Pick the most valuable items that fit by weight.

-   :material-package-variant-closed:{ .lg .middle } **[Container packing](domains/packing.md)**

    ---

    *Where does each box go inside the container?* 3D placement with rotation and stacking
    rules.

-   :material-calendar-check:{ .lg .middle } **[Resource booking](domains/booking.md)**

    ---

    *When does each appointment happen, and with whom?* Doctors, rooms and equipment with
    their own opening hours.

-   :material-content-cut:{ .lg .middle } **[Cutting stock](domains/cutting.md)**

    ---

    *How do I cut these pieces out of my rolls or sheets?* Wastes as little material as
    possible.

</div>

Not sure which one fits? See [Which one do I need?](domains/index.md#which-one-do-i-need)

## Why use it

- **Every problem is written the same way.** Describe it, solve it, read the answer. Learn
  one domain and the other six feel familiar.
- **Answers are plain Java objects.** You read `schedule.makespan()` or `route.stops()`, not
  arrays of solver variables.
- **It tells you how good the answer is.** `OPTIMAL` means proven best. `FEASIBLE` means a
  valid plan that may still be improved. You always know which one you have.
- **It explains failures.** If no plan is possible, it can name the rules that clash, for
  example "this shift needs 2 nurses and only 1 is qualified".
- **Mistakes are caught early.** A typo in a machine name or a negative weight raises a clear
  error that names the culprit, instead of producing a wrong plan.
- **You stay in control.** Set a time limit, watch progress, stop early, or continue from a
  previous plan.

## Next steps

1. [Getting started](guide/getting-started.md): install the library and run your first
   model, step by step.
2. [Core concepts](guide/concepts.md): the handful of ideas shared by all seven domains.
3. Pick your [domain](domains/index.md) and copy its quick start.
4. [Glossary](guide/glossary.md): plain-language meanings of the terms used on this site.

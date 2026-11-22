# Modeling notes

Practical advice and limits worth knowing before you rely on a result. Each section is short
and stands on its own.

## Use whole numbers

All quantities are whole numbers: durations, lengths, weights, costs, penalties. There are no
decimals.

If your data has decimals, pick a smaller unit so that it does not:

| Instead of | Use | Example |
|---|---|---|
| 1.5 hours | minutes | `takes(90)` |
| 2.35 meters | millimeters | `length(2350)` |
| 12.99 euros | cents | `cost(1299)` |
| 0.8 tonnes | kilograms | `weight(800)` |

```java
// Wrong idea: 2.5 kg does not fit in a whole number.
// Right: use grams.
.item("Laptop", i -> i.profit(1200).weight(2500))
.bin("Bag", b -> b.capacity(10_000))        // 10 kg
```

Use the same unit everywhere in one model. Mixing minutes and hours, or kilograms and grams,
gives plans that look valid but are wrong.

**Very large numbers.** If your numbers are so large that a total would not fit in a Java
`long`, orkit refuses the model with a `ModelException` whose message contains `overflows`.
It never continues with a wrong total. Pick a coarser unit if this happens.

## How each domain measures time

| Domain | How time works |
|---|---|
| Job-shop | Plain numbers by default: `takes(10)` means 10 units, and you decide what a unit is. Call `startingAt(...)` to work with real dates and times instead. See [Calendar time](../domains/jobshop.md#calendar-time). |
| Scheduling | Calendar dates. You give a start date and a number of days. Shifts have a start and end time of day and may run past midnight. |
| Routing | Times of day, to the minute, within a single day (00:00 to 24:00). A route cannot span several days. |
| Booking | Real dates and times, to the minute, over a number of days. |

## "Optimal" versus "good"

**Job-shop, scheduling, knapsack, packing, booking and cutting** can prove that a plan is the
best possible. Given enough time they return `OPTIMAL`. If the time runs out first, they
return `FEASIBLE` together with a [gap](diagnostics.md#how-good-is-my-plan) that says how far
off the plan might be.

**Routing** works differently. It builds routes and keeps improving them, but it does not
try to prove that nothing better exists. So:

- `FEASIBLE` is the normal, successful status for a routing plan.
- `OPTIMAL` is rare for routing, and there is never a gap.
- `UNKNOWN` means the time ran out, or you cancelled, before any plan was found.
- `INFEASIBLE` means the engine gave up without finding routes that follow all the rules.
  This is strong evidence that none exist, but not a strict proof.

```java
RoutingPlan plan = problem.solver().timeLimit(Duration.ofSeconds(20)).solve();

if (plan.isFeasible()) {
    // Use the plan. Do not wait for isOptimal(): for routing it is almost never true.
}
```

## Routing

### Travel time does not depend on the time of day

The trip from A to B always takes the same time, at 3 in the morning or in rush hour. Travel
times come from your own table, or from the distance and one average speed
(`travelSpeedKmh`, 40 by default). There is no rush-hour model.

### Straight-line distances are shorter than roads

By default orkit computes distances "as the crow flies" from the coordinates (the technical
name is *haversine* distance). Real roads are longer. For realistic results, pass your own
table of road distances:

```java
.distances(DistanceSource.fromMatrix(metersByRoad))
```

See [Distances and travel times](../domains/routing.md#distances-and-travel-times) for the
layout of the table.

### Coordinates

Latitude must be between -90 and 90, longitude between -180 and 180. Anything else is
rejected:

```text
ModelException: Stop 's': latitude must be within [-90, 90], got: 100.0
```

A common mistake is to swap the two. Latitude comes first: `at(41.39, 2.16)`.

### Choosing the penalty for skipping a stop

When you allow the solver to skip stops, each skipped stop costs a *penalty*. The penalty is
measured in the same unit as the goal. With the default goal that unit is **meters**.

The solver compares two numbers: the extra distance needed to visit the stop, and the
penalty for skipping it. It picks the smaller.

- Penalty **smaller** than the detour: the stop is skipped, even though visiting was
  possible.
- Penalty **larger** than any possible detour: the stop is skipped only when visiting is
  truly impossible.

```java
.allowDroppingStops(50_000)      // skipping costs as much as a 50 km detour
```

If a 50 km detour is larger than anything in your area, stops are skipped only when they
really cannot be served. If you set the penalty to `500`, the solver will skip any stop that
is more than 500 meters out of the way.

With the goal `vehiclesUsed()`, a stop that can be served is never skipped.

## Scheduling: making preferences matter

A roster can have several goals at once: low cost, fair workloads, happy employees. The
solver adds them all into **one score** and makes that score as small as possible. Each goal
has a *weight* that says how much it counts.

The trap: cost is counted in large numbers. A shift costs `costPerHour × minutes`. With an
hourly cost of 40, one 8-hour shift counts as `40 × 480 = 19,200`. Next to that, the default
reward of `5` for a satisfied preference is tiny. It only decides between rosters that cost
the same.

| Goal added with `prefer(...)` | Default weight | Counted per |
|---|---|---|
| `balanceWorkloadAcrossEmployees` | 10 | minute of difference between the busiest and the least busy employee |
| `honorEmployeePreferences` | 5 | preference that is satisfied (a reward, so it lowers the score) |
| `penalizeUnderstaffing` | 100 | missing person on a shift |
| `penalizeContractDeviation` | 1 | minute above or below the contract hours |

**Example.** A senior employee costs 40 per hour and would like the day shift. A junior costs
25 per hour. The shift needs one person, five days in a row.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 5)
        .employee("Senior", e -> e.costPerHour(40).prefersShift("Day"))
        .employee("Junior", e -> e.costPerHour(25))
        .shift("Day", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0)).needs(1))
        .prefer(honorEmployeePreferences(weight))
        .goal(totalLaborCost())
        .solver().solve();
```

| `weight` | Who works | Labor cost | Why |
|---|---|---|---|
| 5 (the default) | Junior, all five days | 1000 | Giving the senior a shift costs 7,200 more and earns a reward of only 5. |
| 10,000 | Senior, all five days | 1600 | Now the reward of 10,000 is larger than the extra cost of 7,200. |

So: to make a preference win against cost, give it a weight in the same range as the cost of
a shift.

!!! tip "Use `laborCost()` when you want money"
    `roster.objective()` is the combined score, in those large internal units.
    `roster.laborCost()` is the plain cost: hours times hourly cost.

## Packing: floating boxes

By default the packing model only makes sure that boxes do not overlap. Nothing stops a box
from hanging in mid-air. For a plan that people can actually load, turn on support:

```java
.requireSupport(true)
```

Every box then stands on the floor or sits on top of **one** other box that is at least as
large underneath. This is stricter than real life: a box may not rest across two boxes, and
may not stick out over an edge. Some loads that a person could build are therefore not
found.

## Cutting

- **Piece numbers on rolls are assigned afterwards.** When you ask for 8 pieces of `Bar-A`,
  the result calls them `Bar-A#1` to `Bar-A#8`, numbered roll by roll. The numbers have no
  meaning beyond telling the pieces apart.
- **Guillotine layouts are simple on purpose.** With `guillotine(true)`, sheets are cut into
  strips and each strip into pieces. Every such layout can be cut edge to edge, but more
  complex edge-to-edge layouts are not tried. The result may use a little more material than
  the best possible guillotine layout. See
  [Guillotine cuts](../domains/cutting.md#guillotine-cuts-sheets-only).

## How big a problem can it handle?

Rough numbers from tests on a laptop, with default settings. Each of these was solved and
**proven best** within about 10 seconds:

| Domain | Size |
|---|---|
| Cutting rolls | 300 pieces of 4 types on 90 rolls |
| Packing | 60 boxes that may be rotated |
| Job-shop | 8 jobs on 5 machines, with a choice of machines |
| Scheduling | 12 employees, 21 shifts, one week |

Larger problems still work. Expect `FEASIBLE` plans with a gap instead of `OPTIMAL`, and
help the solver with a longer time limit or a
[warm start](solver-knobs.md#continuing-from-an-earlier-plan).

The details of a problem matter more than its size. Tight deadlines and many rules can make
a small problem hard, and a large problem with few rules can be easy. Try your own data.

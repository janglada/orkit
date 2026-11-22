# Modeling notes

Limits and conventions worth knowing before you rely on a result.

## Integer arithmetic

All quantities are integers: durations, lengths, weights, costs, penalties. Pick a unit fine
enough for your data (minutes rather than hours, millimeters rather than meters, cents rather
than currency units) and stay consistent within a model.

Inputs whose derived totals (horizons, volumes, areas, weighted costs) would overflow a 64-bit
integer are rejected with `ModelException` instead of wrapping around.

## Time

| Domain | Time model |
|---|---|
| Job-shop | Abstract `long` units by default. Declare `startingAt(origin)` (and optionally `timeUnit(unit)`) to use `LocalDateTime` and `Duration`. |
| Scheduling | Calendar dates over a horizon of days; shifts have `LocalTime` start and end and may cross midnight. |
| Routing | Minutes within one day (00:00 to 24:00). Multi-day routes are not supported. |
| Booking | `LocalDateTime` over a horizon of days, at one-minute resolution. |

## Optimality

CP-SAT domains prove optimality when given enough time; check `isOptimal()` or `gap()`.

Routing uses first-solution strategies plus local-search metaheuristics and does **not** prove
global optimality. `FEASIBLE` is the normal routing status; treat the plan as a high-quality
heuristic.

A routing search that exhausts its time limit, or is cancelled, without any solution reports
`UNKNOWN`. `INFEASIBLE` means OR-Tools either proved infeasibility or exhausted its search
without a solution; because the search is heuristic, the latter is strong evidence, not proof.

## Routing

- **No time-dependent travel.** Travel time is a function of `(from, to)` only. It comes from
  `DistanceSource.fromMatrices` or from the single `travelSpeedKmh` value, never from a
  time-of-day profile.
- **Coordinates** must be valid WGS84 (latitude in [-90, 90], longitude in [-180, 180]).
  Haversine distances are great-circle, not road distances; supply matrices for road networks.
- **Matrices** must be square and non-negative and are copied when supplied. Rows and columns
  are the depots in declaration order, then the stops in declaration order.
- **Drop penalties** are in the units of the objective. Under `totalDistance()` a penalty
  smaller than the detour to a stop makes dropping it the rational choice. Under
  `vehiclesUsed()` a servable stop is never dropped.

## Scheduling weights

The scheduling objective is a single weighted sum. A shift costs `costPerHour × minutes`;
soft goals add or subtract in the same unit:

| Soft goal | Default weight | Per |
|---|---|---|
| `balanceWorkloadAcrossEmployees` | 10 | minute of spread between the most and least loaded employee |
| `honorEmployeePreferences` | 5 | satisfied preference (a reward) |
| `penalizeUnderstaffing` | 100 | employee missing below a shift's target |
| `penalizeContractDeviation` | 1 | minute away from contracted weekly hours |

With an hourly cost of 40, one eight-hour shift weighs 19,200, so the default preference
reward of 5 only breaks ties. Raise a weight to make its goal matter against cost.
`EmployeeRoster.laborCost()` reports the plain cost in currency.

## Packing

Without `requireSupport(true)` the model only forbids overlap, so boxes may float. Turn it on
for physically loadable plans: every box then stands on the floor or rests flush on one box
whose footprint contains its own. A box bridging two supporters is not allowed.

## Cutting

- 1D instance ids (`P#1`, `P#2`, ...) are assigned after the solve, in roll order.
- `guillotine(true)` produces two-stage shelf layouts. These are always cuttable edge to edge,
  but some guillotine layouts with more stages are not reachable, so the result can use more
  stock than a free-form guillotine optimum.

## Scale

Rough guidance from the test suite and probes, on a laptop with default settings:

| Model | Proven optimal within about 10 s |
|---|---|
| 1D cutting | 300 pieces of 4 types on 90 rolls |
| Packing | 60 rotatable boxes |
| Job-shop | 8 jobs on 5 machines with flexible operations |
| Scheduling | 12 employees, 21 shifts, one week |

Beyond that, expect `FEASIBLE` results with a gap, and use `warmStart` and longer time limits.

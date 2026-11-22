# Vehicle routing

Plan routes for a fleet that starts and ends at depots and serves a set of stops, subject to
capacities, time windows, skills and cost.

Package `io.github.janglada.orkit.routing` · entry point `Optimize.vehicleRouting()` · result `RoutingPlan`

!!! info "Heuristic search"
    Routing uses the OR-Tools routing library: a first-solution strategy followed by local
    search. Results are good, usually `FEASIBLE`, and not proven optimal. There is no bound
    or gap.

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.routing.*;
import java.time.Duration;
import java.time.LocalTime;

RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Van-1", v -> v.capacity(20))
        .stop("Cust-A", s -> s.at(41.39, 2.16).demand(4)
                .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(11, 0)))
        .stop("Cust-B", s -> s.at(41.38, 2.19).demand(3)
                .serviceTime(Duration.ofMinutes(10)))
        .distances(DistanceSource.haversine())
        .goal(Goals.totalDistance())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .firstSolution(Strategy.SAVINGS)
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .solve();

for (Route r : plan.routes()) {
    System.out.printf("%s: %s (%.1f km)%n", r.vehicle(), r.stops(), r.distanceMeters() / 1000.0);
}
```

## Goals

| Goal | Minimizes | `objective()` reports |
|---|---|---|
| `Goals.totalDistance()` (default) | Total meters driven. | Meters. |
| `Goals.totalCost()` | Distance cost + time cost + fixed costs + lateness penalties. | That sum. |
| `Goals.vehiclesUsed()` | Vehicles used, then distance as tie-break. | Vehicle count. |

Under `vehiclesUsed()`, serving stops comes first: a droppable stop is left out only when no
vehicle can serve it, never to save a vehicle.

## Distances and travel times

| Source | Distances | Travel times |
|---|---|---|
| `DistanceSource.haversine()` (default) | Great-circle, from coordinates. | From `travelSpeedKmh` (default 40). |
| `DistanceSource.fromMatrix(meters)` | Your matrix. | From `travelSpeedKmh`. |
| `DistanceSource.fromMatrices(meters, minutes)` | Your matrix. | Your matrix. |

Matrix rows and columns are the depots in declaration order, then the stops in declaration
order. With matrices, stops need no coordinates.

## Depots

`depot(id, lat, lon)` is cumulative: call it once per depot. The first is the default.

```java
.depot("West", 41.347, 2.126)
.depot("East", 41.404, 2.200)
.vehicle("Van-1", v -> v.startsAt("West"))
.vehicle("Van-2", v -> v.startsAt("East").endsAt("West"))
```

A vehicle with only `startsAt` or only `endsAt` uses that depot for both ends.

## Capacities

```java
.vehicle("Truck", v -> v.capacity("kg", 900).capacity("pallets", 6))
.stop("Shop", s -> s.at(41.39, 2.16).demand("kg", 120).demand("pallets", 1))
```

- `capacity(n)` and `demand(n)` use the default dimension, named `"load"`.
- Each named dimension is checked independently. A dimension a vehicle does not declare is
  unbounded for that vehicle.
- `Route.loads()` and `Visit.loadsAfter()` report every dimension; `Route.load()` and
  `Visit.loadAfter()` report the default one.
- `VehicleConfig.maxDistanceMeters(m)` caps a vehicle's total distance.

## Time

Times are minutes within one day. The time dimension is modeled, and times are reported, as
soon as any time feature is used.

| Method | Meaning |
|---|---|
| `StopConfig.openFrom(t)`, `closeAt(t)` | Hard window for the start of service. |
| `StopConfig.serviceTime(d)` | Time spent at the stop. |
| `StopConfig.lateAfter(t, penaltyPerMinute)` | Soft deadline: service may start later (up to `closeAt`) at a cost per minute. |
| `VehicleConfig.availableFrom(t)`, `availableUntil(t)` | The vehicle's working window. |
| `VehicleConfig.maxRouteDuration(d)` | Cap on end minus start, waiting included. |
| `VehicleConfig.breakBetween(earliest, latest, d)` | A driver break of length `d` inside the window. |

A break interrupts a drive between two stops, never a service; if the window allows, it may
also fall before the route starts or after it ends. Breaks are reported in `Route.breaks()`.
Lateness is reported in `Visit.minutesLate()`, `Route.latenessPenalty()` and
`RoutingPlan.totalLatenessPenalty()`.

## Costs

| Method | Default | Used by |
|---|---|---|
| `VehicleConfig.fixedCost(c)` | 0 | Charged once per used vehicle; always shapes the search, reported under `totalCost()`. |
| `VehicleConfig.costPerMeter(c)` | 1 | `totalCost()`. |
| `VehicleConfig.costPerMinute(c)` | 0 | `totalCost()`, on route duration. |

With the defaults, cost and meters coincide. `Route.variableCost()` is
`distanceMeters × costPerMeter + duration minutes × costPerMinute`.

## Optional stops

```java
.allowDroppingStops(50_000)                               // every stop, at this penalty
.stop("Low-priority", s -> s.at(41.4, 2.2).penaltyIfDropped(5_000))   // this stop, at its own
```

`penaltyIfDropped` overrides the model-wide penalty for that stop. Dropped stops appear in
`plan.unassignedStops()`. Without any penalty, every stop is mandatory.

## Skills

```java
.vehicle("Reefer", v -> v.skills("cold"))
.stop("Fish", s -> s.at(41.39, 2.16).requires("cold"))
```

A stop is served only by vehicles holding every required skill. A stop nobody can serve fails
fast with `ModelException`.

## Pickup and delivery

```java
.stop("P1", s -> s.at(41.36, 2.10).demand(4))    // load goes on here
.stop("D1", s -> s.at(41.33, 2.05))              // and comes off here
.pickupAndDelivery("P1", "D1")
.pickupAndDeliveryPolicy(PickupDeliveryPolicy.LIFO)
```

The pair is served by one vehicle, pickup first. The pickup's demand is the carried load; the
delivery's demand is omitted or equal. When dropping is allowed, the pair is dropped together,
paying the penalty per stop (the larger of the two if they differ). `FIFO` and `LIFO`
constrain how pairs interleave on a vehicle; `ANY` (default) does not.

## Solver

```java
RoutingSolver solver = problem.solver()
        .timeLimit(Duration.ofSeconds(30))
        .firstSolution(Strategy.PARALLEL_CHEAPEST_INSERTION)
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .onSolution(p -> System.out.println("found " + p.objective().orElseThrow()))
        .warmStart(yesterdaysPlan);
RoutingPlan plan = solver.solve();
```

See [Solver knobs](../guide/solver-knobs.md#routing) for details on each setting.

## Reference

### `RoutingPlan`

| Method | Returns |
|---|---|
| `routes()` | One `Route` per vehicle that serves stops. |
| `routeOf(vehicle)` | That vehicle's route, if any. |
| `unassignedStops()` | Stops dropped or not served. |
| `vehiclesUsed()` | Number of routes. |
| `totalDistanceMeters()`, `totalFixedCost()`, `totalVariableCost()`, `totalLatenessPenalty()` | Totals over routes. |

### `Route`

`vehicle()`, `depotId()`, `stops()`, `visits()`, `distanceMeters()`, `load()`, `loads()`,
`fixedCost()`, `variableCost()`, `latenessPenalty()`, `breaks()`, `startTime()`, `endTime()`,
`hasTimes()`.

### `Visit`

`stop()`, `arrival()`, `departure()`, `loadAfter()`, `loadsAfter()`, `minutesLate()`.
`arrival` is when service starts, after any waiting. Times are `null` when the model has no
time feature.

## Limits

- Routes fit within one day; there are no multi-day routes.
- Travel time does not vary by time of day.
- No worker count or random seed: the routing search offers neither.

# Vehicle routing

**The problem in one sentence:** your vehicles must visit a set of places, and you want to
know which vehicle goes where, and in what order, to drive as little as possible.

A few words used on this page:

- A **depot** is where vehicles start and end their day.
- A **stop** is a place to visit: a customer, a shop, a pickup point.
- A **route** is the ordered list of stops that one vehicle visits.
- A stop's **demand** is how much room it takes in the vehicle, and a vehicle's **capacity**
  is how much it can carry.

Package `io.github.janglada.orkit.routing` · start with `Optimize.vehicleRouting()` · result `RoutingPlan`

!!! info "Routing plans are very good, not proven best"
    Routing uses a different engine from the other domains. It builds routes quickly and
    keeps improving them, but it does not prove that no better plan exists. The normal
    status of a successful routing solve is therefore `FEASIBLE`, not `OPTIMAL`.

## Quick start

One van leaves the depot at 8:00 and visits three customers. Customer A only accepts
deliveries from 9:00 to 11:00, and unloading at customer B takes 10 minutes.

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.routing.*;
import java.time.Duration;
import java.time.LocalTime;

RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)                        // latitude, longitude
        .vehicle("Van-1", v -> v.capacity(20).availableFrom(LocalTime.of(8, 0)))
        .stop("Cust-A", s -> s.at(41.39, 2.16).demand(4)
                .openFrom(LocalTime.of(9, 0)).closeAt(LocalTime.of(11, 0)))
        .stop("Cust-B", s -> s.at(41.38, 2.19).demand(3)
                .serviceTime(Duration.ofMinutes(10)))
        .stop("Cust-C", s -> s.at(41.41, 2.14).demand(5))
        .solver()
        .timeLimit(Duration.ofSeconds(5))
        .solve();

for (Route r : plan.routes()) {
    System.out.printf("%s: %s (%.1f km, load %d)%n",
            r.vehicle(), r.stops(), r.distanceMeters() / 1000.0, r.load());
    for (Visit v : r.visits()) {
        System.out.printf("  %s  arrive %s, leave %s%n", v.stop(), v.arrival(), v.departure());
    }
}
```

Prints:

```text
Van-1: [Cust-B, Cust-A, Cust-C] (18.3 km, load 12)
  Cust-B  arrive 08:09, leave 08:19
  Cust-A  arrive 09:00, leave 09:00
  Cust-C  arrive 09:04, leave 09:04
```

The van goes to B first, because A is not open yet. It then waits and serves A at exactly
9:00.

How to read the model:

- `depot(id, latitude, longitude)` and `at(latitude, longitude)` place things on the map.
  Latitude comes first.
- `capacity(20)` and `demand(4)` use the same unit: boxes, kilograms, whatever you count.
- Distances are worked out from the coordinates. You can also
  [bring your own](#distances-and-travel-times).

## Reading the plan

```java
plan.routes();                 // one Route per vehicle that is used
plan.routeOf("Van-1");         // Optional with that vehicle's route
plan.vehiclesUsed();           // 1
plan.totalDistanceMeters();    // 18260
plan.unassignedStops();        // stops that were skipped (see "Optional stops")
```

A vehicle that has nothing to do does not appear in `routes()`.

For each `Route`:

```java
route.vehicle();          // "Van-1"
route.depotId();          // "Depot": where it starts
route.stops();            // [Cust-B, Cust-A, Cust-C]: names, in visiting order
route.visits();           // the same stops with times and loads
route.distanceMeters();   // 18260
route.load();             // 12: total demand carried
route.startTime();        // 08:00: leaves the depot
route.endTime();          // 09:14: back at the depot
```

For each `Visit`:

```java
visit.stop();          // "Cust-A"
visit.arrival();       // 09:00: when service starts, after any waiting
visit.departure();     // when the vehicle leaves
visit.loadAfter();     // the running total of demand after this stop
visit.minutesLate();   // 0, unless a soft deadline was missed
```

!!! note "Times appear only when you use time features"
    If your model has no opening hours, working hours, service times or breaks, there is
    nothing to schedule. Times are then `null`, and `route.hasTimes()` returns `false`.

## A fleet of identical vehicles

Instead of declaring each vehicle by hand, ask for several at once:

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicles(3, v -> v.capacity(8))          // three identical vans
        .stop("A", s -> s.at(41.39, 2.16).demand(5))
        .stop("B", s -> s.at(41.38, 2.19).demand(5))
        .stop("C", s -> s.at(41.41, 2.14).demand(5))
        .stop("D", s -> s.at(41.33, 2.09).demand(3))
        .solver().solve();

for (Route r : plan.routes()) {
    System.out.printf("%s: %s (%.1f km)%n", r.vehicle(), r.stops(), r.distanceMeters() / 1000.0);
}
```

```text
vehicle-1: [D, C] (20.6 km)
vehicle-2: [B] (12.0 km)
vehicle-3: [A] (10.2 km)
```

The vehicles are named `vehicle-1`, `vehicle-2` and so on. Each van holds 8, so A, B and C
(5 each) cannot share a van. Only D (3) can ride along with another stop.

## Goals: what "best" means

| Goal | In plain words | `objective()` returns |
|---|---|---|
| `Goals.totalDistance()` (default) | Drive as few meters as possible. | Total meters. |
| `Goals.totalCost()` | Spend as little as possible. Cost includes distance, time, a fee per vehicle used, and lateness. | Total cost. |
| `Goals.vehiclesUsed()` | Use as few vehicles as possible. Among plans with the same number, pick the shortest. | Number of vehicles. |

**Example: fewest vehicles.** Three vans are available, but the stops fit in one:

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicles(3, v -> v.capacity(10))
        .stop("A", s -> s.at(41.39, 2.16).demand(4))
        .stop("B", s -> s.at(41.38, 2.19).demand(3))
        .stop("C", s -> s.at(41.33, 2.09).demand(3))
        .goal(Goals.vehiclesUsed())
        .solver().solve();

plan.vehiclesUsed();     // 1
plan.objective();        // OptionalDouble[1.0]
```

With `vehiclesUsed()`, serving customers comes first. The solver never skips a stop just to
save a vehicle.

## Distances and travel times

orkit needs to know how far apart the places are, and how long the drive takes. There are
three ways to tell it.

| Source | Distances come from | Travel times come from |
|---|---|---|
| `DistanceSource.haversine()` (default) | The coordinates, in a straight line. | Distance and `travelSpeedKmh` (40 by default). |
| `DistanceSource.fromMatrix(meters)` | Your table. | Distance and `travelSpeedKmh`. |
| `DistanceSource.fromMatrices(meters, minutes)` | Your table. | Your second table. |

### Straight-line distances

The default. *Haversine* is the name of the formula for the straight-line distance between
two points on the globe. It is quick to set up, but real roads are longer. Good for a first
try, less so for final plans.

```java
.travelSpeedKmh(30)      // average speed in town, used to turn distance into time
```

### Your own distance table

For real road distances, pass a table (a *matrix*). The entry in row `i`, column `j` is the
distance in meters from place `i` to place `j`.

**The order of rows and columns:** all depots first, in the order you declare them, then all
stops, in the order you declare them.

```java
// Row and column order: the depot first, then the stops in the order they are declared.
long[][] meters = {
        //  Depot  Shop-A  Shop-B  Shop-C
        {      0,   4000,   6000,   3000 },   // from Depot
        {   4000,      0,   2500,   5000 },   // from Shop-A
        {   6000,   2500,      0,   4500 },   // from Shop-B
        {   3000,   5000,   4500,      0 },   // from Shop-C
};

RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 0, 0)                 // coordinates are not used with a table
        .vehicle("Van", v -> v.capacity(10))
        .stop("Shop-A", s -> s.demand(2))     // no at(...) needed
        .stop("Shop-B", s -> s.demand(2))
        .stop("Shop-C", s -> s.demand(2))
        .distances(DistanceSource.fromMatrix(meters))
        .solver().solve();

Route route = plan.routes().get(0);
System.out.println(route.stops() + " " + route.distanceMeters() + " m");
```

```text
[Shop-C, Shop-B, Shop-A] 14000 m
```

Check: Depot to C is 3000, C to B is 4500, B to A is 2500, A back to the depot is 4000.
Total 14000.

The table must be square (as many rows as columns, one per place) and must not contain
negative numbers. The two directions may differ (A to B need not equal B to A), which is
handy for one-way streets.

### Your own travel times too

If you also know how long each drive takes, pass a second table with minutes:

```java
long[][] minutes = {
        {  0, 10, 15,  8 },
        { 10,  0,  6, 12 },
        { 15,  6,  0, 11 },
        {  8, 12, 11,  0 },
};

.distances(DistanceSource.fromMatrices(meters, minutes))
```

With a vehicle that starts at 8:00, the same route now arrives at Shop-C at 08:08, Shop-B at
08:19 and Shop-A at 08:25.

## Several depots

Call `depot(...)` once for each depot. A vehicle uses the first depot unless you say
otherwise.

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("West", 41.347, 2.126)
        .depot("East", 41.404, 2.200)
        .vehicle("Van-1", v -> v.startsAt("West").capacity(2))                  // leaves from West, returns to West
        .vehicle("Van-2", v -> v.startsAt("East").endsAt("West").capacity(2))   // leaves from East, finishes at West
        .stop("A", s -> s.at(41.36, 2.11).demand(1))
        .stop("B", s -> s.at(41.40, 2.19).demand(1))
        .stop("C", s -> s.at(41.39, 2.17).demand(1))
        .solver().solve();

for (Route r : plan.routes()) {
    System.out.printf("%s starts at %s: %s%n", r.vehicle(), r.depotId(), r.stops());
}
```

```text
Van-1 starts at West: [A]
Van-2 starts at East: [B, C]
```

If you set only `startsAt` or only `endsAt`, the vehicle uses that depot for both ends.

## Capacity

### One kind of load

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Van-1", v -> v.capacity(10))
        .vehicle("Van-2", v -> v.capacity(10))
        .stop("A", s -> s.at(41.39, 2.16).demand(6))
        .stop("B", s -> s.at(41.38, 2.19).demand(5))
        .stop("C", s -> s.at(41.41, 2.14).demand(4))
        .solver().solve();

for (Route r : plan.routes()) {
    System.out.printf("%s: %s, load %d%n", r.vehicle(), r.stops(), r.load());
}
```

```text
Van-1: [B], load 5
Van-2: [A, C], load 10
```

The demands add up to 15, more than one van holds, so the solver splits the stops over two
vans.

### Several kinds of load

A truck can be full by weight before it is full by volume, or the other way round. Give each
kind of load a name:

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Truck-1", v -> v.capacity("kg", 900).capacity("pallets", 6))
        .vehicle("Truck-2", v -> v.capacity("kg", 900).capacity("pallets", 6))
        .stop("Shop",   s -> s.at(41.39, 2.16).demand("kg", 500).demand("pallets", 2))
        .stop("Market", s -> s.at(41.38, 2.19).demand("kg", 300).demand("pallets", 3))
        .stop("Kiosk",  s -> s.at(41.41, 2.14).demand("kg", 200).demand("pallets", 2))
        .solver().solve();

for (Route r : plan.routes()) {
    System.out.println(r.vehicle() + " " + r.stops() + " carries " + r.loads());
}
```

```text
Truck-1 [Shop, Kiosk] carries {kg=700, pallets=4}
Truck-2 [Market] carries {kg=300, pallets=3}
```

All three stops together would be 1000 kg and 7 pallets. That is too much for one truck on
both counts.

Things to know:

- Each kind of load is checked on its own.
- If a vehicle does not declare a capacity for some kind of load, it has no limit for it.
- `capacity(n)` and `demand(n)` without a name use a kind called `"load"`.
- `route.loads()` and `visit.loadsAfter()` give all kinds. `route.load()` and
  `visit.loadAfter()` give the unnamed one.

### A limit on distance

`maxDistanceMeters(m)` caps how far one vehicle may drive in total.

```java
.vehicle("Van-1", v -> v.maxDistanceMeters(15_000))
.vehicle("Van-2", v -> v.maxDistanceMeters(15_000))
```

In the three-stop example, one van would need 18.3 km. With a limit of 15 km each, the work
is split: `Van-1: [A, C], 14608 m` and `Van-2: [B], 12034 m`.

## Time

All times are times of day, to the minute. A route happens within a single day.

| Method | Kind | In plain words |
|---|---|---|
| `StopConfig.openFrom(t)` | hard | Service cannot start before `t`. A vehicle that arrives early waits. |
| `StopConfig.closeAt(t)` | hard | Service cannot start after `t`. |
| `StopConfig.serviceTime(d)` | | How long the vehicle stays at the stop. |
| `StopConfig.lateAfter(t, penaltyPerMinute)` | soft | Service should start by `t`. Later is allowed, at a cost per minute. |
| `VehicleConfig.availableFrom(t)` | hard | The vehicle cannot leave the depot before `t`. |
| `VehicleConfig.availableUntil(t)` | hard | The vehicle must be back at the depot by `t`. |
| `VehicleConfig.maxRouteDuration(d)` | hard | The whole route, waiting included, takes at most `d`. |
| `VehicleConfig.breakBetween(earliest, latest, d)` | hard | The driver takes a break of length `d` somewhere between `earliest` and `latest`. |

*Hard* means "never broken". *Soft* means "may be broken at a price".

**Example: a delivery day with opening hours, a soft deadline and a lunch break.**

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Van-1", v -> v
                .availableFrom(LocalTime.of(8, 0))
                .availableUntil(LocalTime.of(16, 0))
                .breakBetween(LocalTime.of(10, 0), LocalTime.of(12, 0), Duration.ofMinutes(30)))
        .stop("Bakery", s -> s.at(41.39, 2.16)
                .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(8, 30))    // service must start in this window
                .serviceTime(Duration.ofMinutes(15)))
        .stop("Office", s -> s.at(41.38, 2.19)
                .lateAfter(LocalTime.of(8, 15), 10)                           // each minute after 8:15 costs 10
                .serviceTime(Duration.ofMinutes(20)))
        .stop("School", s -> s.at(41.41, 2.14)
                .openFrom(LocalTime.of(11, 0))                                // not before 11:00
                .serviceTime(Duration.ofMinutes(10)))
        .goal(Goals.totalCost())
        .solver()
        .timeLimit(Duration.ofSeconds(5))
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .solve();

Route route = plan.routes().get(0);
System.out.println("Leaves the depot at " + route.startTime());
for (Visit v : route.visits()) {
    System.out.printf("%-7s arrive %s, leave %s, %d min late%n",
            v.stop(), v.arrival(), v.departure(), v.minutesLate());
}
for (DriverBreak b : route.breaks()) {
    System.out.println("Break   " + b.start() + " to " + b.end());
}
System.out.println("Back at the depot at " + route.endTime());
System.out.println("Lateness penalty: " + plan.totalLatenessPenalty());
```

```text
Leaves the depot at 08:00
Bakery  arrive 08:08, leave 08:23, 0 min late
Office  arrive 08:27, leave 08:47, 12 min late
School  arrive 11:00, leave 11:10, 0 min late
Break   10:00 to 10:30
Back at the depot at 11:20
Lateness penalty: 120
```

The bakery must be served before 8:30, so it comes first. The office is then reached at
8:27, twelve minutes after its soft deadline: 12 minutes at 10 each gives a penalty of 120.
The driver takes the break while waiting for the school to open.

Things to know:

- "Arrive" means the moment service starts. If the vehicle gets there early, the waiting is
  already included.
- A break never interrupts the service at a stop. It happens on the way between two stops,
  or before the route starts, or after it ends, if the break window allows that.
- Combine `lateAfter` with `closeAt` to set a final limit: "should be there by 9:00, must be
  there by 10:00".
- Travel time is the same at any hour. There is no rush-hour model.

## Costs

Under `Goals.totalCost()`, the solver adds up money instead of meters.

| Method | Default | In plain words |
|---|---|---|
| `VehicleConfig.costPerMeter(c)` | 1 | Cost of each meter driven. |
| `VehicleConfig.costPerMinute(c)` | 0 | Cost of each minute between leaving and returning, such as the driver's wage. |
| `VehicleConfig.fixedCost(c)` | 0 | Charged once if the vehicle is used at all, such as a rental fee. |

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Van", v -> v.costPerMeter(2).costPerMinute(50).fixedCost(10_000))
        .stop("A", s -> s.at(41.39, 2.16).serviceTime(Duration.ofMinutes(10)))
        .stop("B", s -> s.at(41.38, 2.19).serviceTime(Duration.ofMinutes(10)))
        .goal(Goals.totalCost())
        .solver().solve();

Route r = plan.routes().get(0);
System.out.printf("%d m, %s to %s%n", r.distanceMeters(), r.startTime(), r.endTime());
System.out.printf("fixed %d + variable %d = %.0f%n",
        r.fixedCost(), r.variableCost(), plan.objective().orElseThrow());
```

```text
13860 m, 00:00 to 00:41
fixed 10000 + variable 29770 = 39770
```

The variable cost is 13,860 meters at 2 each (27,720) plus 41 minutes at 50 each (2,050).

With the default values (1 per meter, nothing else), cost and distance are the same number.

A fixed cost makes the solver think twice before sending out another vehicle. Give a hired
van a high `fixedCost` and it is used only when your own vans cannot do the job.

## Optional stops

By default **every stop must be visited**. If that is impossible (not enough capacity, not
enough time), the result is `INFEASIBLE`.

To let the solver skip stops when it has to, set a *penalty*: the price of skipping one.

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Van", v -> v.capacity(10))
        .allowDroppingStops(50_000)                                   // skipping a stop costs 50,000
        .stop("Big-order",   s -> s.at(41.39, 2.16).demand(6))
        .stop("Small-order", s -> s.at(41.38, 2.19).demand(4))
        .stop("Sample",      s -> s.at(41.41, 2.14).demand(3)
                .penaltyIfDropped(5_000))                             // this one is cheap to skip
        .solver().solve();

System.out.println("Served:  " + plan.routes().get(0).stops());
System.out.println("Skipped: " + plan.unassignedStops());
```

```text
Served:  [Big-order, Small-order]
Skipped: [Sample]
```

The van holds 10 and the three orders need 13. Something has to stay behind, and the sample
is the cheapest to skip.

- `allowDroppingStops(penalty)` sets the price for every stop.
- `penaltyIfDropped(penalty)` on a stop sets its own price. Use a high value for important
  customers and a low one for stops that can wait.

!!! warning "Pick a penalty that is large enough"
    The penalty is compared with the distance saved. If skipping a stop costs 500 and
    visiting it means a 2 km detour, the solver skips it, even though visiting was possible.
    Use a penalty larger than any detour in your area. See
    [Modeling notes](../guide/modeling-notes.md#choosing-the-penalty-for-skipping-a-stop).

## Skills

Some stops need a special vehicle: refrigerated, with a tail lift, with a certified driver.
Give vehicles *skills* and say what each stop *requires*.

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Reefer", v -> v.skills("cold"))          // a refrigerated truck
        .vehicle("Van",    v -> {})                        // an ordinary van
        .stop("Fish",   s -> s.at(41.39, 2.16).requires("cold"))
        .stop("Bread",  s -> s.at(41.38, 2.19))
        .stop("Cheese", s -> s.at(41.41, 2.14).requires("cold"))
        .solver().solve();

for (Route r : plan.routes()) {
    System.out.printf("%s: %s (%.1f km)%n", r.vehicle(), r.stops(), r.distanceMeters() / 1000.0);
}
```

```text
Reefer: [Bread, Fish, Cheese] (18.3 km)
```

Fish and cheese can only travel in the refrigerated truck. The bread could go in either, and
taking it along is shorter than sending the van out.

A stop is served only by a vehicle that has **all** the skills it requires. If no vehicle
qualifies, you find out immediately:

```text
ModelException: Stop 'Fish' requires skills [cold] but no vehicle has them.
```

## Pickup and delivery

Sometimes goods do not come from the depot. They are picked up at one stop and dropped at
another: a courier, a taxi, a furniture mover.

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Depot", 41.35, 2.13)
        .vehicle("Courier", v -> v.capacity(5))
        .stop("Pick-sofa",  s -> s.at(41.36, 2.10).demand(4))    // the sofa goes on here
        .stop("Drop-sofa",  s -> s.at(41.33, 2.05))              // and comes off here
        .stop("Pick-lamp",  s -> s.at(41.40, 2.18).demand(1))
        .stop("Drop-lamp",  s -> s.at(41.42, 2.15))
        .pickupAndDelivery("Pick-sofa", "Drop-sofa")
        .pickupAndDelivery("Pick-lamp", "Drop-lamp")
        .solver().solve();

for (Visit v : plan.routes().get(0).visits()) {
    System.out.printf("%-10s load afterwards: %d%n", v.stop(), v.loadAfter());
}
```

```text
Pick-lamp  load afterwards: 1
Drop-lamp  load afterwards: 0
Pick-sofa  load afterwards: 4
Drop-sofa  load afterwards: 0
```

For each pair:

- The same vehicle does both stops, the pickup first.
- The demand on the **pickup** is what the vehicle carries between the two. Leave the demand
  off the delivery stop.
- If skipping is allowed, both stops are skipped together.

When a vehicle carries several pairs at once, you can control the unloading order:

```java
.pickupAndDeliveryPolicy(PickupDeliveryPolicy.LIFO)
```

| Policy | In plain words |
|---|---|
| `ANY` (default) | No restriction. |
| `LIFO` | Last in, first out: like a van loaded from the back, where the last item loaded is the first one you can reach. |
| `FIFO` | First in, first out: items are delivered in the order they were picked up. |

## Tuning the search

For most problems the defaults are fine. To squeeze out shorter routes, give the solver more
time and a stronger improvement method:

```java
RoutingPlan plan = problem.solver()
        .timeLimit(Duration.ofSeconds(30))
        .firstSolution(Strategy.SAVINGS)
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .solve();
```

[Solver settings](../guide/solver-knobs.md#vehicle-routing-the-routing-solver) explains these
options, and how to watch progress, continue from yesterday's plan, or stop early.

## Limits

- **One day only.** A route cannot run past midnight or span several days.
- **No rush hour.** Travel time between two places is the same at any time of day.
- **No proof of the best plan.** There is no bound and no gap for routing results.
- **No worker count or seed.** The routing engine does not offer them.

## Reference

### `RoutingBuilder`

| Method | Purpose |
|---|---|
| `depot(id, lat, lon)` | Declare a depot. The first one is the default. |
| `vehicle(id, config)` | Declare one vehicle. |
| `vehicles(count, config)` | Declare several identical vehicles, named `vehicle-1`, `vehicle-2`... |
| `stop(id, config)` | Declare a stop. |
| `pickupAndDelivery(pickup, delivery)` | Link two stops as a pickup and its delivery. |
| `pickupAndDeliveryPolicy(policy)` | `ANY`, `LIFO` or `FIFO`. |
| `distances(DistanceSource)` | Where distances and travel times come from. |
| `travelSpeedKmh(double)` | Average speed, when travel times are worked out from distance. |
| `allowDroppingStops(penalty)` | Allow skipping stops, at this price each. |
| `goal(RoutingGoal)` | Choose the goal. |
| `build()`, `solver()` | Freeze the problem, or go straight to solving. |

### `VehicleConfig`

| Method | Purpose |
|---|---|
| `capacity(n)`, `capacity(name, n)` | How much the vehicle carries. |
| `maxDistanceMeters(m)` | Longest total distance. |
| `startsAt(depot)`, `endsAt(depot)` | Start and end depots. |
| `availableFrom(t)`, `availableUntil(t)` | Working hours. |
| `maxRouteDuration(d)` | Longest route, in time. |
| `breakBetween(earliest, latest, d)` | A driver break. Can be called several times. |
| `skills(String...)` | What this vehicle can do. |
| `fixedCost(c)`, `costPerMeter(c)`, `costPerMinute(c)` | Costs. |

### `StopConfig`

| Method | Purpose |
|---|---|
| `at(lat, lon)` | Where the stop is. Not needed with your own distance table. |
| `demand(n)`, `demand(name, n)` | How much room the stop takes in the vehicle. |
| `serviceTime(d)` | Time spent at the stop. |
| `openFrom(t)`, `closeAt(t)` | Opening hours for the start of service. |
| `lateAfter(t, penaltyPerMinute)` | A soft deadline. |
| `requires(String...)` | Skills the vehicle must have. |
| `penaltyIfDropped(penalty)` | This stop's own price for being skipped. |

### `RoutingPlan`

| Method | Returns |
|---|---|
| `routes()` | One `Route` per vehicle that serves at least one stop. |
| `routeOf(vehicle)` | That vehicle's route. Empty if it is not used or the name is unknown. |
| `unassignedStops()` | Names of stops that were skipped. |
| `vehiclesUsed()` | Number of routes. |
| `totalDistanceMeters()` | Sum of all route distances. |
| `totalFixedCost()`, `totalVariableCost()`, `totalLatenessPenalty()` | Cost totals over all routes. |

### `Route`

| Method | Returns |
|---|---|
| `vehicle()`, `depotId()` | The vehicle and its start depot. |
| `stops()` | Stop names in visiting order. |
| `visits()` | One `Visit` per stop, with times and loads. |
| `distanceMeters()` | Length of the route. |
| `load()`, `loads()` | Total demand carried: the unnamed kind, or all kinds by name. |
| `fixedCost()`, `variableCost()`, `latenessPenalty()` | Costs of this route. |
| `breaks()` | The driver's breaks, each with `start()` and `end()`. |
| `startTime()`, `endTime()` | Leaving and returning times. `null` without time features. |
| `hasTimes()` | Whether times are available. |

### `Visit`

| Method | Returns |
|---|---|
| `stop()` | The stop's name. |
| `arrival()` | When service starts, after any waiting. |
| `departure()` | When the vehicle leaves. |
| `loadAfter()`, `loadsAfter()` | The running total of demand after this stop. A delivery that belongs to a pickup takes its load off again. |
| `minutesLate()` | Minutes past the soft deadline, or 0. |

The plan also has `status()`, `objective()` and `runtime()`, as described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer).

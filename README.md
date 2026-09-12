# OR-Tools High-Level DSL

A fluent Java 21 DSL over [Google OR-Tools](https://developers.google.com/optimization) 9.11, covering seven optimization domains: **job-shop scheduling**, **employee shift scheduling**, **vehicle routing**, **multiple knapsack**, **container loading (3D bin packing)**, **resource booking (appointment scheduling)**, and **cutting stock**. Each domain has a small builder API that mirrors how you would describe the problem in prose; the DSL hides the CP-SAT / routing modeling details and returns a typed solution object.

## Requirements

- **Java 21+** and **Maven**.
- OR-Tools native library is pulled automatically per platform. The POM ships the `ortools-linux-x86-64` dependency (runtime scope); `com.google.ortools.Loader` auto-extracts it at startup. macOS arm64 or Windows x86-64 users should swap that artifact for `ortools-darwin-aarch64` or `ortools-win32-x86-64` respectively (same `${ortools.version}`).

## Build & test

```bash
mvn test
```

## Run examples

Seven runnable mains illustrate each domain. The `exec-maven-plugin` is already wired into the build; pass the main class explicitly:

```bash
# PCB flexible job shop (8 board types, 5 machines, makespan minimization)
mvn exec:java -Dexec.mainClass=opt.examples.PcbManufacturingJobShop

# Hospital weekly nurse roster (12 nurses, 21 shifts, labor cost + preferences)
mvn exec:java -Dexec.mainClass=opt.examples.HospitalNurseScheduling

# Barcelona parcel delivery VRP (4 vehicles, 18 stops, time windows, SAVINGS + GLS)
mvn exec:java -Dexec.mainClass=opt.examples.BarcelonaDeliveryVrp

# Cargo loading — multiple knapsack (3 containers, 9 cargo lots, value maximization)
mvn exec:java -Dexec.mainClass=opt.examples.CargoLoadingMultipleKnapsack

# Warehouse container loading — 3D bin packing (2 containers, 8 boxes, minimize containers)
mvn exec:java -Dexec.mainClass=opt.examples.WarehouseContainerPacking

# Clinic appointment booking (3 doctors, 8 appointments, minimize unscheduled penalty)
mvn exec:java -Dexec.mainClass=opt.examples.ClinicAppointmentBooking

# Steel roll cutting stock (3 stock types, 5 piece types, minimize waste)
mvn exec:java -Dexec.mainClass=opt.examples.SteelRollCuttingStock
```

## Quick start

All entry points live on `opt.Optimize`. Each builder ends with `.solver()...solve()`, which returns a typed result (`JobShopSchedule`, `EmployeeRoster`, `RoutingPlan`, `MultipleKnapsackSolution`, `PackingSolution`, `BookingSolution`, `CuttingSolution`). Every builder selects its objective with `.goal(...)` from the domain's `Goals` factory, and `.build()` returns an immutable problem that can be solved repeatedly (see [Solver knobs](#solver-knobs)).

### Job-shop scheduling

```java
import opt.Optimize;
import opt.jobshop.JobShopSchedule;
import java.time.Duration;

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
        .workers(4)
        .solve();

System.out.println(schedule.status() + " makespan=" + schedule.makespan());
```

#### Sequence-dependent setup times

Operations may carry a setup *type* via `.type(...)`, and the builder accepts a
changeover matrix. When a machine switches from an operation of type `A` to one
of type `B`, the gap between them is at least `setup(A, B)`.

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE")
        .setupTime("ALU", "STEEL", 12)           // global: every machine
        .setupTime("STEEL", "ALU", 8)            // asymmetric pairs are allowed
        .setupTimeOn("MILL", "ALU", "STEEL", 20) // machine-specific override
        .defaultSetupTime(5)                     // any unlisted type change
        .initialTypeOn("MILL", "STEEL")          // machine's pre-horizon type
        .job("J1", j -> j.operation().on("MILL").type("ALU").takes(10)
                .then().on("LATHE").type("STEEL").takes(15))
        .job("J2", j -> j.operation().on("LATHE").type("ALU").takes(8))
        .solver().timeLimit(Duration.ofSeconds(15)).solve();
```

Resolution for a transition `fromType → toType` on machine `M`:

1. either side untyped → `0` (`defaultSetupTime` is **never** applied to untyped ops);
2. same typed pair → machine-specific `(T,T)` → global `(T,T)` → `0` (no default);
3. different typed pair → machine-specific → global → `defaultSetupTime` → `0`.

Notes: setup entries must be non-negative; `setupTimeOn`/`initialTypeOn` must
reference declared machines (fails fast with `ModelException`); a matrix type
not used by any operation is reported via `build().warnings()` (matrices may be
shared across models). Flexible (`onAnyOf`) typed operations pay the setup of whichever
machine they land on. With no setup matrix configured, the model behaves exactly
as before (plain `NoOverlap` per machine).

#### Release dates, deadlines, and soft due dates

Each operation may carry a hard time window and/or a soft due date, set via
`.releaseAt(t)`, `.deadlineAt(t)`, and `.dueBy(t)` on the operation configurator.

- `.releaseAt(t)` — earliest start (release date); the start variable is bounded
  below by `t`. Applied as a hard constraint regardless of objective.
- `.deadlineAt(t)` — latest end (hard deadline); the end variable is bounded
  above by `t`. A deadline tighter than the unconstrained optimum forces the
  schedule to compress; one tighter than the work itself is infeasible.
- `.dueBy(t)` — soft due date; tardiness `max(0, end - t)` is penalized in the
  objective only under a tardiness-including goal. Inert under the default
  `MinimizeMakespan` goal.

`releaseAt` + `duration` must not exceed `deadlineAt` (rejected at build time).
The objective is chosen with `.goal(...)`:

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE")
        .goal(Goals.minimizeMakespanAndTardiness(1, 10)) // makespan + 10·tardiness
        .job("J1", j -> j.operation().on("MILL").takes(10).releaseAt(5)
                .then().on("LATHE").takes(15).dueBy(40))
        .job("J2", j -> j.operation().on("LATHE").takes(8).deadlineAt(20))
        .solver().timeLimit(Duration.ofSeconds(15)).solve();
```

Available goals: `Goals.minimizeMakespan()` (default),
`Goals.minimizeTardiness(weight)`,
`Goals.minimizeMakespanAndTardiness(makespanWeight, tardinessWeight)`, and
`Goals.minimizeWeightedCompletionTime()`. Weights must be `> 0`. Flexible
(`onAnyOf`) operations share their start/end across candidate machines, so
release/deadline windows apply without presence gating. Tardiness is reported on
every schedule via `schedule.tardinessOf(job)` / `totalTardiness()`, whatever the goal.

#### Job weights, downtime, and no-wait sequences

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE")
        .downtime("MILL", 100, 160)               // MILL unavailable during [100, 160)
        .goal(Goals.minimizeTardiness(1))
        .job("Rush", j -> j.weight(10).noWait()   // 10x tardiness weight; no idle time between ops
                .operation().on("MILL").takes(20)
                .then().on("LATHE").takes(15).dueBy(40))
        .job("Std", j -> j.maxLag(30)             // at most 30 idle units between consecutive ops
                .operation().on("LATHE").takes(25)
                .then().on("MILL").takes(10).maxLagAfterPrevious(5).dueBy(60))
        .solver().timeLimit(Duration.ofSeconds(15)).solve();

System.out.println("Rush late by " + schedule.tardinessOf("Rush"));
```

- `.downtime(machine, from, to)` is repeatable; overlapping windows are merged. Operations are non-preemptive, so none may straddle a window.
- `.weight(w)` (default 1) multiplies the job's tardiness terms and its completion time under `minimizeWeightedCompletionTime()`.
- `.noWait()` / `.maxLag(lag)` apply to every consecutive pair of a job; `.maxLagAfterPrevious(lag)` overrides for one operation (not allowed on the first).

### Employee shift scheduling

```java
import opt.Optimize;
import opt.scheduling.EmployeeRoster;
import opt.scheduling.ShiftAssignment;
import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;
import java.time.*;
import java.time.DayOfWeek;

EmployeeRoster roster = Optimize.scheduling()
        .horizon(LocalDate.of(2026, 7, 6), 14)
        .employee("Alice", e -> e.skills("RN").costPerHour(50).maxHoursPerWeek(40).prefersShift("Day"))
        .employee("Bob",   e -> e.skills("RN").costPerHour(48).maxHoursPerWeek(40))
        .shift("Day", s -> s.daily()                          // one instance per day of the horizon
                .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                .needs(2).needsAtLeast(1).withSkill("RN")      // 2 wanted, 1 is the hard floor
                .category("DAY"))
        .shift("Night", s -> s.every(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
                .from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0))
                .needs(1).withSkill("RN").category("NIGHT"))
        .assign("Alice", "Day", LocalDate.of(2026, 7, 6))    // lock one instance
        .forbid("Bob", "Night")                               // Bob never works nights
        .constrain(oneShiftPerEmployeePerDay())
        .constrain(minRestBetweenShifts(Duration.ofHours(11)))
        .constrain(forbidSequence("NIGHT", "DAY"))            // no day shift right after a night
        .prefer(balanceWorkloadAcrossEmployees())
        .prefer(honorEmployeePreferences())
        .prefer(penalizeUnderstaffing(500))
        .goal(totalLaborCost())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .workers(4)
        .solve();

for (ShiftAssignment a : roster.assignments()) {
    System.out.printf("%s  %s  %s%n", a.date(), a.shift(), a.employee());
}
System.out.println(roster.staffOf("Night", LocalDate.of(2026, 7, 10)));
```

- **Recurring shifts.** `.daily()`, `.every(DayOfWeek...)`, and `.on(DayOfWeek)` (alias of `every`) expand into one instance per matching date; `.on(LocalDate)` places a single instance. Every instance reports the declared id in `ShiftAssignment.shift()` and is told apart by `date()`; `roster.staffOf(id)` covers all occurrences and `staffOf(id, date)` one of them. A recurrence with no occurrence in the horizon is rejected.
- **Locks.** `.assign(employee, shift)` / `.forbid(employee, shift)` apply to every instance; add a `LocalDate` for one instance. Assigning an ineligible employee, assigning more people than `needs`, or both assigning and forbidding the same pair fails at build time.
- **Forbidden sequences.** Tag shifts with `.category(...)` and use `Rules.forbidSequence(from, to)` to ban a `to` shift on the day after a `from` shift. Unlike `minRestBetweenShifts` this is categorical and day-based.
- **Soft staffing.** `.needsAtLeast(min)` makes `needs(n)` a target; each missing employee is penalized with `Goals.penalizeUnderstaffing(weight)` (default weight 100 when the goal is not declared).
- **Weekly hours.** `maxHoursPerWeek` caps every window of 7 consecutive days, not the whole horizon.

### Vehicle routing

```java
import opt.Optimize;
import opt.routing.*;
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

System.out.println(plan.status() + " distance=" + plan.objective());
```

**Multiple depots.** `depot(...)` is accumulative: call it once per depot (the
first registered depot is the default). Assign vehicles to depots with
`VehicleConfig.startsAt(depotId)` / `endsAt(depotId)` — a vehicle may start and
end at different depots. If neither is set the vehicle uses the default depot
for both ends; depot ids are resolved (and unknown ids rejected) at solve time.

```java
RoutingPlan plan = Optimize.vehicleRouting()
        .depot("West", 41.347, 2.126)          // default depot
        .depot("East", 41.404, 2.200)
        .vehicle("Van-1", v -> v.capacity(20).startsAt("West"))
        .vehicle("Van-2", v -> v.capacity(20).startsAt("East"))
        .stop("Cust-A", s -> s.at(41.39, 2.16).demand(4))
        .stop("Cust-B", s -> s.at(41.40, 2.19).demand(3))
        .distances(DistanceSource.haversine())
        .goal(Goals.totalDistance())
        .solver().timeLimit(Duration.ofSeconds(15)).solve();

for (Route r : plan.routes()) {
    System.out.printf("%s from %s: %s%n", r.vehicle(), r.depotId(), r.stops());
}
```

**Arrival times, vehicle windows, costs, skills, pickup and delivery.**

```java
long[][] meters  = ...;   // from a road-network API; depots first, then stops
long[][] minutes = ...;

RoutingPlan plan = Optimize.vehicleRouting()
        .depot("Hub", 41.35, 2.13)
        .vehicle("Van", v -> v.capacity(20).fixedCost(50_000)
                .availableFrom(LocalTime.of(8, 0)).availableUntil(LocalTime.of(17, 0))
                .maxRouteDuration(Duration.ofHours(6)))
        .vehicle("Reefer", v -> v.capacity(10).fixedCost(80_000).skills("cold"))
        .stop("Fish", s -> s.at(41.39, 2.16).demand(3).requires("cold"))
        .stop("P1", s -> s.at(41.36, 2.10).demand(4))       // pickup: load goes on here
        .stop("D1", s -> s.at(41.33, 2.05))                 // delivery: same load comes off
        .pickupAndDelivery("P1", "D1")
        .pickupAndDeliveryPolicy(PickupDeliveryPolicy.LIFO)
        .distances(DistanceSource.fromMatrices(meters, minutes))
        .goal(Goals.totalCost())                            // distance + fixed cost of used vehicles
        .solver().timeLimit(Duration.ofSeconds(15)).solve();

for (Route r : plan.routes()) {
    System.out.printf("%s leaves %s, back %s, fixed %d%n", r.vehicle(), r.startTime(), r.endTime(), r.fixedCost());
    for (Visit v : r.visits()) {
        System.out.printf("  %s  arrive %s  leave %s  load %d%n", v.stop(), v.arrival(), v.departure(), v.loadAfter());
    }
}
plan.routeOf("Reefer").ifPresent(r -> System.out.println("Reefer: " + r.stops()));
```

- **Times** are reported (`Route.startTime/endTime`, `Visit.arrival/departure`) whenever any time feature is present: a stop window or service time, a vehicle window or `maxRouteDuration`, or a travel-time matrix. A pure CVRP has `hasTimes() == false` and null times.
- **Goals.** `totalDistance()` (default) reports meters; `totalCost()` adds each used vehicle's `fixedCost` (same units as meters); `vehiclesUsed()` minimizes the vehicle count and reports it. `plan.vehiclesUsed()` and `totalFixedCost()` are always available.
- **Pickup and delivery.** The pickup stop's `demand` is the carried load; the delivery stop's demand is omitted or equal. Pairs stay on one vehicle in order and are dropped together (penalty per stop) when `allowDroppingStops` is on. `FIFO`/`LIFO` constrain how pairs interleave.
- **Skills.** `StopConfig.requires(...)` restricts a stop to vehicles declaring every listed skill via `VehicleConfig.skills(...)`; a stop nobody can serve fails fast.
- **Travel times.** `DistanceSource.fromMatrices(meters, minutes)` supplies real travel times and makes `travelSpeedKmh` irrelevant.

### Multiple knapsack

```java
import opt.Optimize;
import opt.knapsack.BinAssignment;
import opt.knapsack.MultipleKnapsackSolution;
import java.time.Duration;

MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
        .bin("T1", b -> b.capacity(10))
        .bin("T2", b -> b.capacity(10))
        .item("I1", i -> i.profit(7).weight(3))
        .item("I2", i -> i.profit(5).weight(6))
        .item("I3", i -> i.profit(9).weight(5))
        .item("I4", i -> i.profit(4).weight(2))
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .workers(4)
        .solve();

System.out.println(sol.status() + " totalProfit=" + sol.totalProfit());
for (BinAssignment a : sol.assignments()) {
    System.out.printf("  %s: %s (load %d/%d, profit %d)%n",
            a.bin(), a.items(), a.usedCapacity(), a.capacity(), a.profit());
}
```

Items may be left out of every bin when total weight exceeds total capacity; those appear in `sol.unassignedItems()`. Use `sol.binOf(item)` / `sol.assignmentOf(bin)` (both return `Optional`) to look up individual placements.

### Container loading (3D bin packing)

```java
import opt.Optimize;
import opt.packing.*;
import java.time.Duration;

PackingSolution sol = Optimize.packing()
        .container("40ft", c -> c.length(1200).width(235).height(269).maxWeight(28000))
        .container("20ft", c -> c.length(586).width(235).height(269).maxWeight(22000))
        .box("Pallet-A", b -> b.length(120).width(80).height(150).weight(800))
        .box("Pallet-B", b -> b.length(120).width(80).height(150).weight(1200)
                .rotatable(true)               // may be reoriented to any axis-permutation
                .maxStackedWeight(900))        // caps total weight resting on this box
        .box("Machine-X", b -> b.length(200).width(100).height(180).weight(4500)
                .below("Pallet-B"))           // Pallet-B must sit directly on Machine-X
        .requireSupport(true)                  // every resting box must be footprint-supported
        .goal(Goals.minimizeContainersUsed())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .workers(4)
        .solve();

System.out.println(sol.status() + " containersUsed=" + sol.containersUsed());
for (ContainerAssignment a : sol.assignments()) {
    System.out.printf("  %s: %d boxes, %d/%d vol, %d/%d wt%n",
            a.containerId(), a.boxes().size(),
            a.usedVolume(), a.length()*a.width()*a.height(),
            a.usedWeight(), a.maxWeight());
    for (ContainerAssignment.BoxPlacement p : a.boxes()) {
        System.out.printf("    %s at (%d,%d,%d) dim %dx%dx%d%s%n",
                p.boxId(), p.x(), p.y(), p.z(), p.length(), p.width(), p.height(),
                p.orientation() == null ? "" : " rotated " + p.orientation());
    }
}
```

Boxes too large for any container appear in `sol.unassignedBoxes()`. Each `ContainerAssignment` carries per-box `BoxPlacement` records with origin coordinates (x, y, z) and the applied `Orientation` (null for non-rotatable boxes) for 3D non-overlap verification.

Beyond the classical no-overlap + weight-limit model, boxes opt into stacking sub-features: `.rotatable(true)` lets the solver choose any distinct axis-permutation of the box dimensions; `.maxStackedWeight(w)` caps the total weight of boxes resting on a supporting box; `.above(id)` / `.below(id)` declare an explicit vertical order between two boxes; and `.requireSupport(true)` enforces that every box resting on another has its XY footprint contained within the supporter below. All default off, preserving the classical model.

### Resource booking (appointment scheduling)

```java
import opt.Optimize;
import opt.booking.*;
import java.time.*;

BookingSolution plan = Optimize.booking()
        .horizon(LocalDate.of(2026, 7, 6), 5)
        .resource("Dr-Chen", r -> r
                .availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0))      // mornings
                .available(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0))
                .available(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(17, 0)))
        .resource("Dr-Patel", r -> r
                .available(new TimeSlot(                                       // explicit slot
                        LocalDateTime.of(2026, 7, 6, 8, 0),
                        LocalDateTime.of(2026, 7, 6, 16, 0))))
        .appointment("A1", a -> a.duration(Duration.ofMinutes(30))
                .buffer(Duration.ofMinutes(10))                                // room turnaround
                .requiresAnyOf("Dr-Chen", "Dr-Patel"))
        .appointment("A2", a -> a.duration(Duration.ofMinutes(60))
                .requiresAnyOf("Dr-Chen")
                .earliest(LocalDateTime.of(2026, 7, 6, 10, 0))
                .penaltyIfUnscheduled(50))
        .goal(Goals.minimizeUnscheduledPenalty())
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .workers(4)
        .solve();

System.out.println(plan.status() + " objective=" + plan.objective());
for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("  %s -> %s at %s-%s%n",
            a.appointmentId(), a.resourceId(), a.start().toLocalTime(), a.end().toLocalTime());
}
```

Availability may be given as explicit `TimeSlot`s, weekly windows (`available(DayOfWeek, from, to)`), or daily windows (`availableDaily(from, to)`), in any combination; overlapping windows are merged and an appointment must fit entirely inside one window. A `buffer` keeps the resource blocked after the appointment without appearing in the reported `end()`. Declarations may come in any order; times are resolved against the horizon when the problem is built. Appointments may declare `earliest`/`latest` bounds and an unscheduled penalty; `plan.unscheduled()` lists those that could not be placed.

### Cutting stock

```java
import opt.Optimize;
import opt.cutting.*;
import java.time.Duration;

CuttingSolution sol = Optimize.cuttingStock()
        .stock("R1", s -> s.length(6000).cost(100).quantity(5))
        .stock("R2", s -> s.length(8000).cost(130).quantity(3))
        .required("Bar-A", p -> p.length(1500).quantity(8))
        .required("Bar-B", p -> p.length(1200).quantity(6))
        .required("Bar-C", p -> p.length(900).quantity(4))
        .goal(Goals.minimizeWaste())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .workers(4)
        .solve();

System.out.println(sol.status() + " waste=" + sol.totalWaste());
for (StockAssignment a : sol.assignments()) {
    System.out.printf("  %s (len=%d): %d pieces, waste=%d%n",
            a.stockId(), a.length(), a.pieces().size(), a.waste());
}
```

All required pieces must be placed; the model is infeasible if total piece length exceeds total stock length. `quantity` expands a stock or piece type into instances whose ids are `"<declared id>#<n>"` (for example `R1#2`); `StockAssignment.stockType()` and `CutPiece.pieceType()` return the declared id. `MinimizeWaste` minimizes total length consumed; `MinimizeStockUsed` minimizes the number of rolls used (different behaviors when rolls have different lengths).

**2D non-guillotine cutting.** Declare a `.width(...)` on every stock and piece to switch to the 2D path: each stock becomes a sheet (length × width) and pieces are placed as rectangles with X/Y origins, subject to pairwise 2D no-overlap constraints. `MinimizeWaste` is dimension-aware — it minimizes total length consumed in 1D but total **area** consumed (`used[r] * length * width`) in 2D; `MinimizeStockUsed` (sheet count) and `MinimizeTotalCost` work the same in either dimension. Mixed 1D/2D models (some items with width, some without) are rejected. Guillotine cutting is planned; 2D non-guillotine is supported today.

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("sheet", s -> s.length(200).width(150).cost(120).quantity(2))
        .required("blank-A", p -> p.length(60).width(40).quantity(4))
        .required("blank-B", p -> p.length(50).width(30).quantity(3))
        .goal(Goals.minimizeWaste())   // minimizes total area consumed
        .solver().timeLimit(Duration.ofSeconds(15)).workers(8).solve();
for (StockAssignment a : sol.assignments()) {
    System.out.printf("  %s (%dx%d): %d pieces, area waste=%d%n",
            a.stockId(), a.length(), a.width(), a.pieces().size(), a.waste());
    for (CutPiece c : a.pieces()) {
        System.out.printf("    %s %dx%d at (%d,%d)%n", c.pieceId(), c.length(), c.width(), c.x(), c.y());
    }
}
```

## Result API

Every `solve()` returns a `Solution` subtype exposing:

- `status()` — a `SolveStatus`: `OPTIMAL`, `FEASIBLE`, `INFEASIBLE`, `UNBOUNDED`, `UNKNOWN`, `MODEL_INVALID`.
- `objective()` — `OptionalDouble` (empty if the model has no objective or the solver didn't prove one).
- `runtime()` — `Duration` spent solving.
- `isFeasible()` — `true` when status is `OPTIMAL` or `FEASIBLE`.
- `isOptimal()` — `true` only when the solver proved global optimality.

Lookups such as `MultipleKnapsackSolution.binOf(item)` or `PackingSolution.containerOf(box)` return `Optional` (empty for an unassigned or unknown id). A built problem exposes `warnings()` for non-fatal diagnostics.

## Solver knobs

`build()` returns an immutable problem; `solver()` on it (or directly on the builder) returns a fresh solver each call.

- **Every solver** (`opt.Solver`): `timeLimit(Duration)`, `verbose(boolean)`, `solve()`.
- **CP-SAT domains** (`opt.CpSatSolver`, all except routing): `workers(int)` (default **8**, CP-SAT's tuned portfolio size), `seed(int)` (reproducible only with `workers(1)`), `onSolution(Consumer<S>)` to receive each improving intermediate result, `warmStart(S previous)` to hint the search from an earlier feasible result (ids that no longer exist are ignored), and `cancel()` to stop a running solve and keep the best result so far.
- **Routing** (`opt.routing.RoutingSolver`): `firstSolution(Strategy)` and `improveWith(Metaheuristic)`. OR-Tools routing search has no worker count or seed, so those knobs are deliberately absent.

```java
var problem = Optimize.jobShop()....build();
var first = problem.solver().timeLimit(Duration.ofSeconds(10)).solve();
var solver = problem.solver().timeLimit(Duration.ofMinutes(5)).warmStart(first);
var better = solver.onSolution(s -> { if (s.makespan() < 100) solver.cancel(); }).solve();
```

## Modeling notes

- **Routing optimality.** Routing uses first-solution strategies plus local-search metaheuristics (e.g. SAVINGS + Guided Local Search) and does **not** prove global optimality the way CP-SAT does. `FEASIBLE` is the normal status for routing; `OPTIMAL` is reported only on the rare occasion OR-Tools proves it. Treat routing results as high-quality heuristics, not certifiably optimal solutions.
- **Routing status.** A routing search that exhausts its time limit without any solution reports `UNKNOWN` (raise the limit); `INFEASIBLE` means the search space was exhausted.
- **No time-dependent travel.** Routing's travel time is a pure function of `(from, to)` — OR-Tools' native `RoutingModel` transit callback cannot read the arrival-time variable, so travel time cannot vary by time of day. Travel times come from `DistanceSource.fromMatrices` or from the single global `travelSpeedKmh` scalar, never from a time-banded profile. Static time windows, vehicle windows, and multiple depots are fully supported; true time-dependent travel would require a CP-SAT routing solver and is not yet implemented.
- **Soft-goal weights.** Scheduling soft goals accept an optional weight folded into the same objective as labor cost. `Goals.balanceWorkloadAcrossEmployees(weight)` defaults to **10** per minute of imbalance; `Goals.honorEmployeePreferences(weight)` defaults to **5** per satisfied preference (reward); `Goals.penalizeUnderstaffing(weight)` defaults to **100** per missing employee. All are in the same units as the `totalLaborCost()` objective (cost per hour times minutes). Pass a larger weight (e.g. `honorEmployeePreferences(100)`) to make preferences outweigh cost savings, or a smaller one to prioritize cost.
- **Shift placement.** `.on(DayOfWeek)` and `.every(...)` place a shift on **every** matching date in the horizon; `.on(LocalDate)` targets one calendar date. Assignments carry the declared shift id plus the concrete `date()`.

## Project layout

Public API lives in package `opt` (entry point `Optimize`) plus the domain packages `opt.jobshop`, `opt.scheduling`, `opt.routing`, `opt.knapsack`, `opt.packing`, `opt.booking`, and `opt.cutting`. All OR-Tools modeling detail is confined to `*.internal` subpackages and is not part of the supported API.

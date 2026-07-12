# OR-Tools High-Level DSL

A fluent Java 25 DSL over [Google OR-Tools](https://developers.google.com/optimization) 9.11, covering seven optimization domains: **job-shop scheduling**, **employee shift scheduling**, **vehicle routing**, **multiple knapsack**, **container loading (3D bin packing)**, **resource booking (appointment scheduling)**, and **cutting stock**. Each domain has a small builder API that mirrors how you would describe the problem in prose; the DSL hides the CP-SAT / routing modeling details and returns a typed solution object.

## Requirements

- **Java 51+** and **Maven**.
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

All entry points live on `opt.Optimize`. Each builder ends with `.solver()...solve()`, which returns a typed result (`JobShopSchedule`, `EmployeeRoster`, `RoutingPlan`, `MultipleKnapsackSolution`, `PackingSolution`, `BookingSolution`, `CuttingSolution`).

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
not used by any operation logs a warning to `stderr` (matrices may be shared
across models). Flexible (`onAnyOf`) typed operations pay the setup of whichever
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
The objective is chosen with `.minimize(...)`:

```java
JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE")
        .minimize(Goals.minimizeMakespanAndTardiness(1, 10)) // makespan + 10·tardiness
        .job("J1", j -> j.operation().on("MILL").takes(10).releaseAt(5)
                .then().on("LATHE").takes(15).dueBy(40))
        .job("J2", j -> j.operation().on("LATHE").takes(8).deadlineAt(20))
        .solver().timeLimit(Duration.ofSeconds(15)).solve();
```

Available goals: `Goals.minimizeMakespan()` (default),
`Goals.minimizeTardiness(weight)`, and
`Goals.minimizeMakespanAndTardiness(makespanWeight, tardinessWeight)`. Weights
must be `> 0`. Flexible (`onAnyOf`) operations share their start/end across
candidate machines, so release/deadline windows apply without presence gating.

### Employee shift scheduling

```java
import opt.Optimize;
import opt.scheduling.EmployeeRoster;
import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;
import java.time.*;
import java.time.DayOfWeek;

EmployeeRoster roster = Optimize.scheduling()
        .horizon(LocalDate.of(2026, 7, 6), 7)
        .employee("Alice", e -> e.skills("RN").costPerHour(50).maxHoursPerWeek(40))
        .employee("Bob",   e -> e.skills("RN").costPerHour(48).maxHoursPerWeek(40))
        .shift("Mon-Day", s -> s.on(DayOfWeek.MONDAY)
                .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                .needs(1).withSkill("RN"))
        .constrain(oneShiftPerEmployeePerDay())
        .constrain(minRestBetweenShifts(Duration.ofHours(11)))
        .prefer(balanceWorkloadAcrossEmployees())
        .prefer(honorEmployeePreferences())
        .minimize(totalLaborCost())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .workers(4)
        .solve();
```

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
        .minimize(Goals.totalDistance())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .firstSolution(Strategy.SAVINGS)
        .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
        .solve();

System.out.println(plan.status() + " distance=" + plan.objective());
```

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

Items may be left out of every bin when total weight exceeds total capacity; those appear in `sol.unassignedItems()`. Use `sol.binOf(item)` / `sol.assignmentOf(bin)` to look up individual placements.

### Container loading (3D bin packing)

```java
import opt.Optimize;
import opt.packing.*;
import java.time.Duration;

PackingSolution sol = Optimize.packing()
        .container("40ft", c -> c.length(1200).width(235).height(269).maxWeight(28000))
        .container("20ft", c -> c.length(586).width(235).height(269).maxWeight(22000))
        .box("Pallet-A", b -> b.length(120).width(80).height(150).weight(800))
        .box("Pallet-B", b -> b.length(120).width(80).height(150).weight(1200))
        .box("Machine-X", b -> b.length(200).width(100).height(180).weight(4500))
        .minimize(Goals.minimizeContainersUsed())
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
}
```

Boxes too large for any container appear in `sol.unassignedBoxes()`. Each `ContainerAssignment` carries per-box `BoxPlacement` records with origin coordinates (x, y, z) for 3D non-overlap verification.

### Resource booking (appointment scheduling)

```java
import opt.Optimize;
import opt.booking.*;
import java.time.*;

BookingSolution plan = Optimize.booking()
        .horizon(LocalDate.of(2026, 7, 6), 1)
        .resource("Dr-Chen", r -> r
                .available(new TimeSlot(
                        LocalDateTime.of(2026, 7, 6, 9, 0),
                        LocalDateTime.of(2026, 7, 6, 12, 0)))
                .available(new TimeSlot(
                        LocalDateTime.of(2026, 7, 6, 13, 0),
                        LocalDateTime.of(2026, 7, 6, 17, 0))))
        .resource("Dr-Patel", r -> r
                .available(new TimeSlot(
                        LocalDateTime.of(2026, 7, 6, 8, 0),
                        LocalDateTime.of(2026, 7, 6, 16, 0))))
        .appointment("A1", a -> a.duration(Duration.ofMinutes(30))
                .requiresAnyOf("Dr-Chen", "Dr-Patel"))
        .appointment("A2", a -> a.duration(Duration.ofMinutes(60))
                .requiresAnyOf("Dr-Chen")
                .earliest(LocalDateTime.of(2026, 7, 6, 10, 0))
                .penaltyIfUnscheduled(50))
        .minimize(Goals.minimizeUnscheduledPenalty())
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .workers(4)
        .solve();

System.out.println(plan.status() + " objective=" + plan.objective());
for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("  %s -> %s at %d-%d%n",
            a.appointmentId(), a.resourceId(), a.startMinute(), a.endMinute());
}
```

Resources have one or more availability slots (lunch breaks, off-hours). Appointments may optionally declare `earliest`/`latest` time windows and an unscheduled penalty. Use `plan.unscheduled()` to list appointments that could not be placed.

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
        .minimize(Goals.minimizeWaste())
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

All required pieces must be placed; the model is infeasible if total piece length exceeds total stock length. `quantity` expands a stock or piece type into multiple instances. `MinimizeWaste` minimizes total length consumed; `MinimizeStockUsed` minimizes the number of rolls used (different behaviors when rolls have different lengths).

## Result API

Every `solve()` returns a `Solution` subtype exposing:

- `status()` — a `SolveStatus`: `OPTIMAL`, `FEASIBLE`, `INFEASIBLE`, `UNBOUNDED`, `UNKNOWN`, `MODEL_INVALID`.
- `objective()` — `OptionalDouble` (empty if the model has no objective or the solver didn't prove one).
- `runtime()` — `Duration` spent solving.
- `isFeasible()` — `true` when status is `OPTIMAL` or `FEASIBLE`.
- `isOptimal()` — `true` only when the solver proved global optimality.

## Modeling notes

- **Routing optimality.** Routing uses first-solution strategies plus local-search metaheuristics (e.g. SAVINGS + Guided Local Search) and does **not** prove global optimality the way CP-SAT does. `FEASIBLE` is the normal status for routing; `OPTIMAL` is reported only on the rare occasion OR-Tools proves it. Treat routing results as high-quality heuristics, not certifiably optimal solutions.
- **Soft-goal weights.** Scheduling soft goals accept an optional weight folded into the same objective as labor cost. `Goals.balanceWorkloadAcrossEmployees(weight)` defaults to **10** per minute of imbalance; `Goals.honorEmployeePreferences(weight)` defaults to **5** per satisfied preference (reward). Both are in the same units as the `totalLaborCost()` objective. Pass a larger weight (e.g. `honorEmployeePreferences(100)`) to make preferences outweigh cost savings, or a smaller one to prioritize cost.
- **Shift placement.** `.on(DayOfWeek)` places a shift on the **first** matching date in the horizon. For recurring weekly shifts across a multi-week horizon, define one shift per occurrence (distinct ids) or use `.on(LocalDate)` to target a specific calendar date.

## Project layout

Public API lives in package `opt` (entry point `Optimize`) plus the domain packages `opt.jobshop`, `opt.scheduling`, `opt.routing`, `opt.knapsack`, `opt.packing`, `opt.booking`, and `opt.cutting`. All OR-Tools modeling detail is confined to `*.internal` subpackages and is not part of the supported API.

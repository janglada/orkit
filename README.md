# OR-Tools High-Level DSL

A fluent Java 21 DSL over [Google OR-Tools](https://developers.google.com/optimization) 9.11, covering four optimization domains: **job-shop scheduling**, **employee shift scheduling**, **vehicle routing**, and **multiple knapsack**. Each domain has a small builder API that mirrors how you would describe the problem in prose; the DSL hides the CP-SAT / routing modeling details and returns a typed solution object.

## Requirements

- **Java 21+** and **Maven**.
- OR-Tools native library is pulled automatically per platform. The POM ships the `ortools-linux-x86-64` dependency (runtime scope); `com.google.ortools.Loader` auto-extracts it at startup. macOS arm64 or Windows x86-64 users should swap that artifact for `ortools-darwin-aarch64` or `ortools-win32-x86-64` respectively (same `${ortools.version}`).

## Build & test

```bash
mvn test
```

## Run examples

Four runnable mains illustrate each domain. The `exec-maven-plugin` is already wired into the build; pass the main class explicitly:

```bash
# PCB flexible job shop (8 board types, 5 machines, makespan minimization)
mvn exec:java -Dexec.mainClass=opt.examples.PcbManufacturingJobShop

# Hospital weekly nurse roster (12 nurses, 21 shifts, labor cost + preferences)
mvn exec:java -Dexec.mainClass=opt.examples.HospitalNurseScheduling

# Barcelona parcel delivery VRP (4 vehicles, 18 stops, time windows, SAVINGS + GLS)
mvn exec:java -Dexec.mainClass=opt.examples.BarcelonaDeliveryVrp

# Cargo loading — multiple knapsack (3 containers, 9 cargo lots, value maximization)
mvn exec:java -Dexec.mainClass=opt.examples.CargoLoadingMultipleKnapsack
```

## Quick start

All entry points live on `opt.Optimize`. Each builder ends with `.solver()...solve()`, which returns a typed result (`JobShopSchedule`, `EmployeeRoster`, `RoutingPlan`, `MultipleKnapsackSolution`).

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

Public API lives in package `opt` (entry point `Optimize`) plus the domain packages `opt.jobshop`, `opt.scheduling`, `opt.routing`, and `opt.knapsack`. All OR-Tools modeling detail is confined to `*.internal` subpackages and is not part of the supported API.

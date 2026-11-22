# Job-shop scheduling

Schedule jobs made of ordered operations on machines. Each operation runs on one machine (or
one of several candidates) for a fixed duration, without interruption.

Package `io.github.janglada.orkit.jobshop` · entry point `Optimize.jobShop()` · result `JobShopSchedule`

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.jobshop.JobShopSchedule;
import java.time.Duration;

JobShopSchedule schedule = Optimize.jobShop()
        .machines("MILL", "LATHE", "DRILL")
        .job("J1", j -> j
                .operation().on("MILL").takes(10)
                .then().on("LATHE").takes(20)
                .then().on("DRILL").takes(5))
        .job("J2", j -> j
                .operation().onAnyOf("LATHE", "MILL").takes(15)   // flexible: either machine
                .then().on("DRILL").takes(10))
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

System.out.println(schedule.status() + " makespan=" + schedule.makespan());
schedule.timelineOf("DRILL").forEach(op ->
        System.out.printf("%s[%d] %d..%d%n", op.job(), op.index(), op.start(), op.end()));
```

Inside a job, start with `operation()` and chain further operations with `then()`. Operations
of a job run in declaration order.

## Goals

| Goal | Minimizes |
|---|---|
| `Goals.minimizeMakespan()` (default) | The time the last operation ends. |
| `Goals.minimizeTardiness(weight)` | Weighted sum of `max(0, end - due)` over operations with a due date. |
| `Goals.minimizeMakespanAndTardiness(wMakespan, wTardiness)` | Both, with the given weights. |
| `Goals.minimizeWeightedCompletionTime()` | Sum of each job's completion time times its weight. |

Weights must be positive. Tardiness is reported on every schedule via `tardinessOf(job)` and
`totalTardiness()`, whatever the goal.

## Time windows and due dates

```java
Optimize.jobShop()
        .machines("MILL", "LATHE")
        .goal(Goals.minimizeMakespanAndTardiness(1, 10))
        .job("J1", j -> j.operation().on("MILL").takes(10).releaseAt(5)
                .then().on("LATHE").takes(15).dueBy(40))
        .job("J2", j -> j.operation().on("LATHE").takes(8).deadlineAt(20));
```

| Method | Kind | Meaning |
|---|---|---|
| `releaseAt(t)` | hard | Earliest start. |
| `deadlineAt(t)` | hard | Latest end. A deadline the work cannot meet makes the model infeasible. |
| `dueBy(t)` | soft | Lateness is penalized under a tardiness goal; inert under `minimizeMakespan()`. |

`releaseAt + duration` must not exceed `deadlineAt` (rejected when the operation is declared).

## Job options

```java
.job("Rush", j -> j.weight(10).noWait()
        .operation().on("MILL").takes(20)
        .then().on("LATHE").takes(15).dueBy(40))
.job("Std", j -> j.maxLag(30)
        .operation().on("LATHE").takes(25)
        .then().on("MILL").takes(10).maxLagAfterPrevious(5))
```

| Method | Meaning |
|---|---|
| `weight(w)` | Relative importance (default 1): multiplies the job's tardiness and completion-time terms. |
| `noWait()` | Every operation starts exactly when the previous one ends. |
| `maxLag(lag)` | At most `lag` idle time between consecutive operations. |
| `maxLagAfterPrevious(lag)` | The same for one operation, overriding the job setting (not allowed on the first). |

## Sequence-dependent setup times

Tag operations with a setup *type*; when a machine switches from type `A` to type `B`, the gap
between the two operations is at least `setup(A, B)`.

```java
Optimize.jobShop()
        .machines("MILL", "LATHE")
        .setupTime("ALU", "STEEL", 12)             // every machine
        .setupTime("STEEL", "ALU", 8)              // asymmetric pairs are allowed
        .setupTimeOn("MILL", "ALU", "STEEL", 20)   // machine-specific override
        .defaultSetupTime(5)                       // any unlisted type change
        .initialTypeOn("MILL", "STEEL")            // the machine's type before time 0
        .job("J1", j -> j.operation().on("MILL").type("ALU").takes(10)
                .then().on("LATHE").type("STEEL").takes(15))
        .job("J2", j -> j.operation().on("LATHE").type("ALU").takes(8));
```

Resolution for a transition `from → to` on machine `M`:

1. Either side untyped → `0` (the default setup never applies to untyped operations).
2. Same type → machine-specific entry → global entry → `0`.
3. Different types → machine-specific entry → global entry → `defaultSetupTime` → `0`.

A matrix type that no operation uses is reported through `build().warnings()`. Flexible
operations pay the setup of whichever machine they land on.

## Downtime

```java
.downtime("MILL", 100, 160)      // MILL unavailable during [100, 160)
```

Repeatable; overlapping windows on one machine are merged. Operations are not preemptive, so
none may straddle a window.

## Precedences between jobs

```java
.precedence("Frame", "Assembly")           // Assembly starts after Frame's last operation ends
.precedence("Door", 1, "Assembly", 0)      // operation 1 of Door before operation 0 of Assembly
```

Jobs may be declared later. Unknown ids, out-of-range operation indices (0-based) and cycles
are rejected at build time.

## Parallel machines

```java
.machine("Oven", 2)      // runs up to two operations at once
```

A machine declared with `machine(id, capacity)` runs up to `capacity` operations at the same
time. Downtime blocks the whole machine. Sequence-dependent setup times need a capacity-1
machine; combining them is rejected. The result does not say which "lane" an operation used.

## Calendar time

By default all times are abstract `long` units. Anchor the model to a calendar to work with
`LocalDateTime` and `Duration`:

```java
LocalDateTime monday = LocalDateTime.of(2026, 7, 6, 8, 0);

JobShopSchedule schedule = Optimize.jobShop()
        .startingAt(monday).timeUnit(Duration.ofMinutes(15))   // one unit = 15 minutes
        .machines("Press")
        .downtime("Press", monday.plusHours(4), monday.plusHours(5))
        .job("Door", j -> j.operation().on("Press").takes(Duration.ofMinutes(45))
                .releaseAt(monday.plusMinutes(30))
                .dueBy(monday.plusHours(6)))
        .solver().solve();

schedule.operationsOf("Door").get(0).startTime();   // LocalDateTime
schedule.makespanDuration();                        // Optional<Duration>
```

- `startingAt(origin)` sets what model time 0 means; `timeUnit(unit)` defaults to one minute.
  Both must be called before any job is declared.
- Durations and instants must fall exactly on the unit grid; anything else is rejected, never
  rounded. Instants before the origin are rejected.
- The `long` methods keep working alongside and are measured in the same units.
- Without a calendar, `startTime()` and `endTime()` are `null` and `makespanDuration()` is empty.

## Reference

### `JobShopBuilder`

| Method | Purpose |
|---|---|
| `machines(String...)` | Declare unit-capacity machines. |
| `machine(String, int)` | Declare a machine with a capacity. |
| `job(String, Consumer<JobConfig>)` | Declare a job and its operations. |
| `precedence(...)` | Order operations of different jobs. |
| `setupTime`, `setupTimeOn`, `defaultSetupTime`, `initialTypeOn` | Setup matrix. |
| `downtime(machine, from, to)` | Machine unavailability (`long` or `LocalDateTime`). |
| `startingAt(LocalDateTime)`, `timeUnit(Duration)` | Calendar. |
| `goal(JobShopGoal)` | Objective. |
| `build()`, `solver()`, `warnings()` | Problem, solver, diagnostics. |

### `OperationConfig`

`on(machine)`, `onAnyOf(machines...)`, `takes(int | Duration)`, `type(setupType)`,
`releaseAt`, `deadlineAt`, `dueBy` (each `long` or `LocalDateTime`),
`maxLagAfterPrevious(long)`, `then()`.

### `JobShopSchedule`

| Method | Returns |
|---|---|
| `makespan()` | End of the last operation, in model units. |
| `makespanDuration()` | The same as a `Duration`, with a calendar. |
| `operations()` | Every `ScheduledOperation`. |
| `timelineOf(machine)` | Operations on a machine, by start time. |
| `operationsOf(job)` | Operations of a job, in order. |
| `tardinessOf(job)`, `totalTardiness()` | Lateness against due dates. |
| `asGantt()` | A `Gantt` view: machines and their rows. |

`ScheduledOperation` has `job()`, `index()`, `machine()`, `start()`, `end()`, `startTime()`,
`endTime()`.

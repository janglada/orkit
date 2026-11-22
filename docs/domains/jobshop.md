# Job-shop scheduling

**The problem in one sentence:** several jobs each go through a list of steps on machines,
and you want to know when each step should run so that everything finishes as early as
possible.

A few words used on this page:

- A **job** is a piece of work, such as one customer order.
- An **operation** is one step of a job: it needs a machine for a fixed time.
- A **machine** does one operation at a time, and an operation is never paused once it has
  started.
- The **makespan** is the time at which the very last operation ends.

"Machine" can be anything that is busy while it works: an oven, a printer, a test bench, a
person.

Package `io.github.janglada.orkit.jobshop` · start with `Optimize.jobShop()` · result `JobShopSchedule`

## Quick start

Two jobs, three machines.

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
                .operation().onAnyOf("LATHE", "MILL").takes(15)   // either machine will do
                .then().on("DRILL").takes(10))
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

System.out.println(schedule.status() + " makespan=" + schedule.makespan());
schedule.timelineOf("DRILL").forEach(op ->
        System.out.printf("%s[%d] %d..%d%n", op.job(), op.index(), op.start(), op.end()));
```

Prints:

```text
OPTIMAL makespan=40
J2[1] 25..35
J1[2] 35..40
```

How to read the model:

- Inside a job, the first step starts with `operation()` and each further step with `then()`.
  The steps run in the order you write them. A step can start only after the previous step
  of the same job has ended.
- `on("MILL")` fixes the machine. `onAnyOf("LATHE", "MILL")` lets the solver pick one.
- `takes(10)` is the duration. The unit is up to you (minutes, hours...), as long as you use
  the same one everywhere. To work with real dates, see [Calendar time](#calendar-time).

How to read the output: `J2[1] 25..35` means step number 1 of job J2 (steps are numbered from
0) runs on the drill from time 25 to time 35.

## Reading the schedule

There are three ways to look at the same plan.

**By machine**, to see what each machine does:

```java
Gantt gantt = schedule.asGantt();
for (String machine : gantt.machines()) {
    System.out.print(machine + ":");
    for (ScheduledOperation op : gantt.rowFor(machine)) {
        System.out.printf("  %s %d-%d", op.job(), op.start(), op.end());
    }
    System.out.println();
}
```

```text
MILL:  J1 0-10  J2 10-25
LATHE:  J1 10-30
DRILL:  J2 25-35  J1 35-40
```

**By job**, to tell a customer when their order is done:

```java
for (ScheduledOperation op : schedule.operationsOf("J2")) {
    System.out.printf("step %d on %s from %d to %d%n", op.index(), op.machine(), op.start(), op.end());
}
```

```text
step 0 on MILL from 10 to 25
step 1 on DRILL from 25 to 35
```

Notice that the solver chose the mill for the first step of J2, because the lathe was busy
with J1.

**Everything at once:** `schedule.operations()` returns every scheduled step.

## Letting the solver choose the machine

When several machines can do the same step, list them with `onAnyOf`. The solver balances
the work.

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("PRINTER-A", "PRINTER-B")
        .job("Poster", j -> j.operation().onAnyOf("PRINTER-A", "PRINTER-B").takes(30))
        .job("Flyer",  j -> j.operation().onAnyOf("PRINTER-A", "PRINTER-B").takes(20))
        .job("Banner", j -> j.operation().on("PRINTER-A").takes(25))     // only A is wide enough
        .solver().solve();

s.operations().forEach(op ->
        System.out.printf("%s runs on %s, %d..%d%n", op.job(), op.machine(), op.start(), op.end()));
```

```text
Poster runs on PRINTER-B, 0..30
Flyer runs on PRINTER-A, 25..45
Banner runs on PRINTER-A, 0..25
```

## Goals: what "best" means

| Goal | In plain words |
|---|---|
| `Goals.minimizeMakespan()` (default) | Finish the whole batch as early as possible. |
| `Goals.minimizeTardiness(weight)` | Be late as little as possible. Only steps with a `dueBy` count. |
| `Goals.minimizeMakespanAndTardiness(wMakespan, wTardiness)` | Both. The two weights say how much each one counts. |
| `Goals.minimizeWeightedCompletionTime()` | Finish each job early, important jobs first. |

*Tardiness* means lateness: how long after its due time a step ends. A step that ends on time
or early has a tardiness of 0. Weights must be positive whole numbers.

**Example: important jobs first.** One desk, three tasks. The urgent quote is five times as
important as the others:

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("DESK")
        .goal(Goals.minimizeWeightedCompletionTime())
        .job("Big-report",   j -> j.operation().on("DESK").takes(60))
        .job("Quick-fix",    j -> j.operation().on("DESK").takes(5))
        .job("Urgent-quote", j -> j.weight(5).operation().on("DESK").takes(20))
        .solver().solve();

s.timelineOf("DESK").forEach(op ->
        System.out.printf("%-12s %d..%d%n", op.job(), op.start(), op.end()));
```

```text
Urgent-quote 0..20
Quick-fix    20..25
Big-report   25..85
```

With the default goal the order would not matter: the last task always ends at 85.

## Time limits on a step

Three settings control when a step may run:

| Method | Kind | In plain words |
|---|---|---|
| `releaseAt(t)` | hard | The step cannot start before `t`. Example: the material arrives at `t`. |
| `deadlineAt(t)` | hard | The step must end by `t`. A plan that misses it is not allowed. |
| `dueBy(t)` | soft | The step should end by `t`. Being late is allowed, but counts against the plan under a tardiness goal. |

*Hard* means "never broken". *Soft* means "broken only when necessary".

**Example: one oven, three orders.**

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("OVEN")
        .goal(Goals.minimizeTardiness(1))
        .job("Bread", j -> j.operation().on("OVEN").takes(40).dueBy(40))
        .job("Cake",  j -> j.operation().on("OVEN").takes(30).dueBy(50))
        .job("Pizza", j -> j.operation().on("OVEN").takes(15).releaseAt(20).deadlineAt(60))
        .solver().solve();

for (ScheduledOperation op : s.timelineOf("OVEN")) {
    System.out.printf("%-6s %3d..%-3d late by %d%n",
            op.job(), op.start(), op.end(), s.tardinessOf(op.job()));
}
System.out.println("Total lateness: " + s.totalTardiness());
```

```text
Bread    0..40  late by 0
Pizza   40..55  late by 0
Cake    55..85  late by 35
Total lateness: 35
```

The pizza has a hard deadline of 60, so it must go before the cake. The cake is then 35
late, which is allowed because `dueBy` is soft.

Things to know:

- `dueBy` has no effect under the default goal `minimizeMakespan()`. Choose a tardiness goal
  to make it count.
- `tardinessOf(job)` and `totalTardiness()` are filled in whatever the goal.
- A step whose release time plus duration is already past its deadline is rejected as soon
  as you declare it.
- If the deadlines cannot all be met, the result is `INFEASIBLE`. See
  [how to find out which ones clash](../guide/diagnostics.md#example-impossible-deadlines).

## Job options

These settings apply to a whole job. Write them before the first `operation()`.

| Method | In plain words |
|---|---|
| `weight(w)` | How important the job is (default 1). A job with weight 10 counts ten times as much in tardiness and completion-time goals. |
| `noWait()` | No pauses inside the job: each step starts the moment the previous one ends. Example: hot metal that must not cool down. |
| `maxLag(lag)` | A pause between two steps may last at most `lag`. Example: glue that dries. |

One step can have its own limit with `maxLagAfterPrevious(lag)`. It overrides the job
setting for the pause before that step. It makes no sense on the first step and is rejected
there.

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("MILL", "LATHE")
        .goal(Goals.minimizeMakespanAndTardiness(1, 10))
        .job("Rush", j -> j.weight(10).noWait()
                .operation().on("MILL").takes(20)
                .then().on("LATHE").takes(15).dueBy(40))
        .job("Std", j -> j.maxLag(30)
                .operation().on("LATHE").takes(25)
                .then().on("MILL").takes(10).maxLagAfterPrevious(5))
        .solver().solve();

s.operations().forEach(op -> System.out.printf("%s[%d] %s %d..%d%n",
        op.job(), op.index(), op.machine(), op.start(), op.end()));
```

```text
Rush[0] MILL 5..25
Rush[1] LATHE 25..40
Std[0] LATHE 0..25
Std[1] MILL 25..35
```

`Rush` starts at 5 rather than 0. Because of `noWait()`, its second step must start exactly
when its first one ends, and the lathe is busy until 25.

## Setup times

Sometimes a machine needs preparation between two different kinds of work: cleaning between
paint colors, changing a tool, heating up. This is called a *setup time*, and it often
depends on what came before.

Give each step a **type** with `type(...)`, then say how long each change of type takes.

**Example: a paint booth.** Going from white to black needs a quick rinse (5). Going from
black to white needs a full clean (30).

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("PAINT")
        .setupTime("WHITE", "BLACK", 5)       // a quick rinse
        .setupTime("BLACK", "WHITE", 30)      // a full clean
        .job("Door-1", j -> j.operation().on("PAINT").type("BLACK").takes(10))
        .job("Door-2", j -> j.operation().on("PAINT").type("WHITE").takes(10))
        .job("Door-3", j -> j.operation().on("PAINT").type("BLACK").takes(10))
        .solver().solve();

s.timelineOf("PAINT").forEach(op ->
        System.out.printf("%s %d..%d%n", op.job(), op.start(), op.end()));
```

```text
Door-2 0..10
Door-3 15..25
Door-1 25..35
```

The solver paints the white door first, rinses for 5, then paints both black doors back to
back. That avoids the expensive black-to-white clean completely.

### All the setup settings

```java
Optimize.jobShop()
        .machines("MILL", "LATHE")
        .setupTime("ALU", "STEEL", 12)             // on every machine
        .setupTime("STEEL", "ALU", 8)              // the two directions may differ
        .setupTimeOn("MILL", "ALU", "STEEL", 20)   // a different value for one machine
        .defaultSetupTime(5)                       // any change of type not listed above
        .initialTypeOn("MILL", "STEEL")            // what the mill was doing before time 0
        .job("J1", j -> j.operation().on("MILL").type("ALU").takes(10)
                .then().on("LATHE").type("STEEL").takes(15))
        .job("J2", j -> j.operation().on("LATHE").type("ALU").takes(8));
```

Which value applies when a machine goes from type `A` to type `B`? The first rule that
matches:

1. If either step has no type, the setup is 0.
2. A value set with `setupTimeOn` for that machine.
3. A value set with `setupTime`.
4. If the types differ, the `defaultSetupTime`. If they are the same, 0.

A type that appears in a setup rule but on no step is probably a typo. It is reported in
[`build().warnings()`](../guide/concepts.md#warnings). When the solver chooses the machine
(`onAnyOf`), the step pays the setup of the machine it ends up on.

## Downtime

*Downtime* is a period when a machine cannot work: maintenance, a lunch break, the night.

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("MILL")
        .downtime("MILL", 30, 50)             // unavailable from 30 up to (not including) 50
        .job("A", j -> j.operation().on("MILL").takes(25))
        .job("B", j -> j.operation().on("MILL").takes(20))
        .solver().solve();

s.timelineOf("MILL").forEach(op ->
        System.out.printf("%s %d..%d%n", op.job(), op.start(), op.end()));
```

```text
A 0..25
B 50..70
```

Job B takes 20, and only 5 are left before the downtime starts. A step cannot be paused and
resumed, so B waits until 50.

You can declare several downtimes on the same machine. Overlapping ones are merged into one.

## One job before another

Use `precedence` when a whole job, or one step of it, must wait for another job.

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("WELDER", "PAINT", "BENCH")
        .job("Frame",    j -> j.operation().on("WELDER").takes(40))
        .job("Door",     j -> j.operation().on("WELDER").takes(15)
                .then().on("PAINT").takes(20))
        .job("Assembly", j -> j.operation().on("BENCH").takes(30))
        .precedence("Frame", "Assembly")          // Assembly starts after all of Frame is done
        .precedence("Door", 1, "Assembly", 0)     // step 1 of Door (paint) before step 0 of Assembly
        .solver().solve();

s.operations().forEach(op -> System.out.printf("%-8s step %d on %-6s %3d..%d%n",
        op.job(), op.index(), op.machine(), op.start(), op.end()));
```

```text
Frame    step 0 on WELDER  15..55
Door     step 0 on WELDER   0..15
Door     step 1 on PAINT   20..40
Assembly step 0 on BENCH   55..85
```

Assembly starts at 55, once the frame is welded and the door is painted.

- The two-argument form means "the first step of the second job starts after the last step
  of the first job ends".
- The four-argument form names exact steps. Steps are numbered from 0.
- Rules that go in a circle (A before B and B before A) are rejected:
  `the precedences form a cycle through operation 'A#0'`.

## Machines that do several things at once

Some machines can run more than one operation at the same time: an oven with two trays, a
team of three people. Declare them with `machine(id, capacity)`.

```java
JobShopSchedule s = Optimize.jobShop()
        .machine("OVEN", 2)                   // two trays fit at once
        .job("Bread", j -> j.operation().on("OVEN").takes(40))
        .job("Cake",  j -> j.operation().on("OVEN").takes(30))
        .job("Pizza", j -> j.operation().on("OVEN").takes(15))
        .solver().solve();

s.timelineOf("OVEN").forEach(op ->
        System.out.printf("%s %d..%d%n", op.job(), op.start(), op.end()));
```

```text
Bread 0..40
Cake 0..30
Pizza 30..45
```

Bread and cake bake together. The pizza goes in when the cake comes out.

Things to know:

- Downtime stops the whole machine, all trays at once.
- Setup times need a machine that does one thing at a time. Using both on the same machine
  is rejected.
- The result does not say which tray each operation used.

## Calendar time

So far, times were plain numbers. To work with real dates and clock times, tell the builder
when the schedule starts.

```java
LocalDateTime monday = LocalDateTime.of(2026, 7, 6, 8, 0);     // Monday, 08:00

JobShopSchedule schedule = Optimize.jobShop()
        .startingAt(monday).timeUnit(Duration.ofMinutes(15))   // plan in steps of 15 minutes
        .machines("Press")
        .downtime("Press", monday.plusHours(4), monday.plusHours(5))   // lunch, 12:00 to 13:00
        .job("Door", j -> j.operation().on("Press").takes(Duration.ofMinutes(45))
                .releaseAt(monday.plusMinutes(30))
                .dueBy(monday.plusHours(6)))
        .job("Hood", j -> j.operation().on("Press").takes(Duration.ofHours(4)))
        .solver().solve();

for (ScheduledOperation op : schedule.timelineOf("Press")) {
    System.out.printf("%s: %s to %s%n", op.job(), op.startTime(), op.endTime());
}
System.out.println("Total time: " + schedule.makespanDuration().orElseThrow());
```

```text
Hood: 2026-07-06T08:00 to 2026-07-06T12:00
Door: 2026-07-06T13:00 to 2026-07-06T13:45
Total time: PT5H45M
```

(`PT5H45M` is how Java prints a duration of 5 hours 45 minutes.)

How it works:

- `startingAt(origin)` says which real moment is "time 0".
- `timeUnit(unit)` is the smallest step of the plan. It defaults to one minute.
- Call both **before** you declare any job.
- Once a calendar is set, you can pass a `Duration` to `takes(...)` and a `LocalDateTime` to
  `releaseAt`, `deadlineAt`, `dueBy` and `downtime`.
- Read the answer with `startTime()` and `endTime()` on each operation, and
  `makespanDuration()` on the schedule.

Things to know:

- **Times must fit the unit exactly.** With a 15-minute unit, a 20-minute step is rejected.
  Nothing is ever rounded for you:
  `takes() PT20M is not a whole number of time units (PT15M).`
- A time before the start of the schedule is rejected.
- The plain-number methods still work and count in units. With a 15-minute unit, `takes(3)`
  means 45 minutes, and `op.start()` returns a number of units.
- Without a calendar, `startTime()` and `endTime()` return `null` and `makespanDuration()` is
  empty.

## Reference

### `JobShopBuilder`

| Method | Purpose |
|---|---|
| `machines(String...)` | Declare machines that do one thing at a time. |
| `machine(String, int)` | Declare a machine that does several things at once. |
| `job(String, Consumer<JobConfig>)` | Declare a job and its steps. |
| `precedence(before, after)` | The whole job `before` ends before job `after` starts. |
| `precedence(jobA, stepA, jobB, stepB)` | One step ends before another step starts. |
| `setupTime(from, to, time)` | Setup time between two types, on every machine. |
| `setupTimeOn(machine, from, to, time)` | The same for one machine only. |
| `defaultSetupTime(time)` | Setup time for any other change of type. |
| `initialTypeOn(machine, type)` | The type the machine is set up for at the start. |
| `downtime(machine, from, to)` | The machine is unavailable (numbers or `LocalDateTime`). |
| `startingAt(LocalDateTime)`, `timeUnit(Duration)` | Use real dates and times. |
| `goal(JobShopGoal)` | Choose the goal. |
| `build()`, `solver()` | Freeze the problem, or go straight to solving. |
| `warnings()` | Odd things noticed in the model. |

### `JobConfig` (inside `job(...)`)

| Method | Purpose |
|---|---|
| `weight(long)` | Importance of the job. |
| `noWait()` | No pauses between steps. |
| `maxLag(long)` | Longest pause allowed between steps. |
| `operation()` | Start the first step. |

### `OperationConfig` (one step)

| Method | Purpose |
|---|---|
| `on(machine)` | The machine that runs this step. |
| `onAnyOf(machines...)` | Let the solver pick one of these machines. |
| `takes(int)` or `takes(Duration)` | How long the step lasts. |
| `type(setupType)` | The kind of work, for setup times. |
| `releaseAt(...)` | Earliest start (number or `LocalDateTime`). |
| `deadlineAt(...)` | Latest end, never broken. |
| `dueBy(...)` | Wanted end, may be missed at a price. |
| `maxLagAfterPrevious(long)` | Longest pause before this step. |
| `then()` | Start the next step of the same job. |

### `JobShopSchedule`

| Method | Returns |
|---|---|
| `makespan()` | When the last step ends, as a number of units. |
| `makespanDuration()` | The same as a `Duration`. Empty without a calendar. |
| `operations()` | Every scheduled step. |
| `timelineOf(machine)` | The steps on one machine, in time order. |
| `operationsOf(job)` | The steps of one job, in order. |
| `tardinessOf(job)` | How late the job is against its due times. |
| `totalTardiness()` | The sum over all jobs. |
| `asGantt()` | A `Gantt` view: `machines()` and `rowFor(machine)`. |

### `ScheduledOperation`

| Method | Returns |
|---|---|
| `job()` | The job's name. |
| `index()` | The step number inside the job, starting at 0. |
| `machine()` | The machine it runs on. |
| `start()`, `end()` | Start and end, as numbers of units. |
| `startTime()`, `endTime()` | The same as `LocalDateTime`. `null` without a calendar. |

The schedule also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

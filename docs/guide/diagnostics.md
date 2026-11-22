# Diagnostics

A result tells you more than the plan. This page shows how to answer three questions:

1. [How good is my plan?](#how-good-is-my-plan)
2. [Why is there no plan?](#finding-out-why-there-is-no-plan)
3. [What is the solver doing?](#seeing-what-the-solver-does)

## How good is my plan?

When the status is `OPTIMAL`, the plan is the best possible and there is nothing more to ask.

When the status is `FEASIBLE`, the plan is valid but the solver ran out of time before it
could prove that it is the best. Two numbers tell you how far off it might be.

### The bound

While it searches, the solver also works out a limit that **no plan can beat**. This limit
is called the *bound*.

An example: you are minimizing the length of a schedule. The solver has a plan that takes
1414 minutes. It has also proven that no plan can take less than 1073 minutes. The true best
is somewhere between 1073 and 1414.

- When you **minimize**, the bound is a floor: no plan can score lower.
- When you **maximize**, the bound is a ceiling: no plan can score higher.

### The gap

The *gap* is the distance between your plan and the bound, as a fraction of the plan's
score:

```text
gap = |score - bound| / |score|
```

With the numbers above: `(1414 - 1073) / 1414 = 0.24`, or 24%. So the best possible plan is
**at most** 24% shorter than yours. Your plan may be much closer than that, or even the best
already; the solver just has not proven it yet.

(For scores between -1 and 1 the formula divides by 1 instead, to avoid dividing by zero.)

### Reading them

```java
JobShopSchedule s = problem.solver().timeLimit(Duration.ofSeconds(1)).solve();

if (s.isOptimal()) {
    System.out.println("Optimal makespan " + s.makespan());
} else if (s.isFeasible()) {
    System.out.printf("Makespan %d. The best possible is at most %.1f%% lower (bound %.0f).%n",
            s.makespan(), 100 * s.gap().orElseThrow(), s.bestBound().orElseThrow());
}
```

On a hard problem (15 jobs on 15 machines) cut off after one second, this prints something
like:

```text
Makespan 1414. The best possible is at most 24.1% lower (bound 1073).
```

Running the same problem for ten seconds returns `OPTIMAL` with a makespan of 1242. The true
best was 12% lower than the first plan, well inside the 24% that the gap allowed for.

### How to use the gap

| Gap | What it means | What to do |
|---|---|---|
| `0` | Proven best. The status is `OPTIMAL`. | Nothing. |
| Small, such as 1% | The plan is close to the best possible. | Usually good enough to use. |
| Large, such as 40% | The solver has not narrowed things down yet. | Run longer, or [continue from this plan](solver-knobs.md#continuing-from-an-earlier-plan). |

A common pattern is to stop as soon as the plan is within a few percent:

```java
CpSatSolver<JobShopSchedule> solver = problem.solver().timeLimit(Duration.ofMinutes(10));
solver.onSolution(s -> {
    if (s.gap().orElse(1.0) < 0.02) solver.cancel();    // within 2% is good enough
});
JobShopSchedule schedule = solver.solve();
```

!!! note "Routing has no bound and no gap"
    The routing engine improves routes step by step, but it does not work out a limit. For
    routing plans, `bestBound()` and `gap()` are always empty. Treat a routing plan as "very
    good", not "proven best".

`bestBound()` and `gap()` are also empty when there is no plan, or when the model has no goal
to score.

## Finding out why there is no plan

`INFEASIBLE` means your rules cannot all be true at the same time. The status alone does not
say which rules clash. Turn on `explainInfeasibility(true)` and the solver tells you.

### Example: not enough qualified staff

A shift needs two registered nurses (`RN`), but only one employee has that skill:

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(LocalDate.of(2026, 7, 6), 2)
        .employee("a", e -> e.skills("RN"))
        .employee("b", e -> e.skills("LPN"))
        .shift("day", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                .needs(2).withSkill("RN"))
        .solver()
        .explainInfeasibility(true)
        .solve();

System.out.println(roster.status());
roster.diagnostics().infeasibilityReasons().forEach(System.out::println);
```

Prints:

```text
INFEASIBLE
staffing: shift 'day' on 2026-07-06 needs 2 qualified employee(s)
```

### Example: two rules that clash

One employee, allowed to work at most 24 hours a week, and an 8-hour shift every day:

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(LocalDate.of(2026, 7, 6), 7)
        .employee("Alice", e -> e.maxHoursPerWeek(24))
        .shift("Day", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0)).needs(1))
        .solver().explainInfeasibility(true).solve();

roster.diagnostics().infeasibilityReasons().forEach(System.out::println);
```

Prints:

```text
staffing: shift 'Day' on 2026-07-06 needs 1 qualified employee(s)
staffing: shift 'Day' on 2026-07-07 needs 1 qualified employee(s)
staffing: shift 'Day' on 2026-07-08 needs 1 qualified employee(s)
staffing: shift 'Day' on 2026-07-09 needs 1 qualified employee(s)
maxHoursPerWeek(24) of 'Alice'
```

Read it as: "these rules cannot all hold together". Four shifts are 32 hours, and Alice may
work only 24. To fix the model, relax one of them: hire a second person, or raise the limit.

### Example: impossible deadlines

Two jobs need the same machine for 30 minutes each, and both must be finished by minute 40:

```java
JobShopSchedule s = Optimize.jobShop()
        .machines("PRESS")
        .job("Door", j -> j.operation().on("PRESS").takes(30).deadlineAt(40))
        .job("Hood", j -> j.operation().on("PRESS").takes(30).deadlineAt(40))
        .solver()
        .explainInfeasibility(true)
        .solve();

s.diagnostics().infeasibilityReasons().forEach(System.out::println);
```

Prints:

```text
deadline 40 of operation 0 of job 'Door'
deadline 40 of operation 0 of job 'Hood'
```

### Example: pieces that do not fit

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("Roll", s -> s.length(100))
        .required("Strip", p -> p.length(30).quantity(4))     // 120 needed, 100 available
        .solver().explainInfeasibility(true).solve();

sol.diagnostics().infeasibilityReasons();
// [all 4 piece(s) 'Strip' must be cut]
```

### Which rules can be named

| Domain | Rules that can appear in the list |
|---|---|
| Scheduling | The staffing of each shift on each date, role counts, `assign` and `forbid`, weekly maximum and minimum hours of each employee, and every rule added with `constrain(...)`, per employee. |
| Job-shop | Release times, deadlines, the order of steps inside a job, maximum waiting times, and "this job before that job". |
| Cutting | "All pieces of type X must be cut" (rolls) or "piece X#n must be cut" (sheets). |
| Packing | "Box X must be packed" and each container's weight limit. |

### Things to know

- **The list is enough, but maybe not the shortest.** The rules in the list truly cannot all
  hold together. Sometimes a smaller part of the list would already be enough to cause the
  clash.
- **Some rules cannot be named.** Basic physics, such as "a machine does one thing at a
  time" or "two boxes cannot overlap", is always taken for granted. If such rules alone make
  the model impossible, the list comes back empty.
- **It costs one extra solve, only on failure.** A model that has a plan is solved once, as
  usual. Only an infeasible model is solved a second time to find the reasons.
- **It does nothing for knapsack, booking and routing.** Knapsack and booking models always
  have a plan (items and appointments may simply be left out). Routing uses a different
  engine that cannot explain itself.

## Seeing what the solver does

### Search statistics

Every result from a CP-SAT domain carries a few numbers about the search:

```java
SolveDiagnostics d = schedule.diagnostics();

d.bestBound();              // OptionalDouble[287.0]: the limit described above
d.branches();               // 6869: how many decisions the solver tried
d.conflicts();              // 1249: how many dead ends it hit
d.infeasibilityReasons();   // []: filled only on request, see above
```

`branches` and `conflicts` are rough measures of effort. A problem that needs millions of
them is hard; one that needs a few hundred is easy. They are also filled in on the
in-between results passed to [`onSolution`](solver-knobs.md#watching-progress).

Routing results carry no statistics: `diagnostics()` returns an empty record.

### The progress log

```java
problem.solver().verbose(true).solve();
```

Prints the solver's own log to the console: how it simplified the model, each better plan,
and how the bound moved. See [Solver settings](solver-knobs.md#progress-log).

### The model file

`exportModel(Path)` saves the mathematical model for inspection with other tools. See
[Solver settings](solver-knobs.md#saving-the-model-to-a-file).

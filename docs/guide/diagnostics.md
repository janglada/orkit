# Diagnostics

Every result carries a `SolveDiagnostics` record. CP-SAT domains fill it in on every result,
intermediate ones included. Routing search is heuristic and proves no bound, so routing
results carry `SolveDiagnostics.none()`.

| Field | Meaning |
|---|---|
| `bestBound()` | Best objective bound proven so far: a lower bound when minimizing, an upper bound when maximizing. Empty without an objective or without a solution. |
| `branches()` | Search branches explored. |
| `conflicts()` | Conflicts encountered. |
| `infeasibilityReasons()` | Labels of constraint groups that cannot hold together. Filled only on request, see below. |

## Bound and gap

`Solution.bestBound()` and `Solution.gap()` are shortcuts. The gap is relative:
`|objective - bound| / max(1, |objective|)`. It is `0` for a proven optimum.

```java
JobShopSchedule s = problem.solver().timeLimit(Duration.ofSeconds(30)).solve();

if (s.isOptimal()) {
    System.out.println("Optimal makespan " + s.makespan());
} else if (s.isFeasible()) {
    System.out.printf("Makespan %d, at most %.1f%% above the optimum (bound %.0f)%n",
            s.makespan(), 100 * s.gap().orElseThrow(), s.bestBound().orElseThrow());
}
```

Use the gap to decide whether a longer run is worth it: a `FEASIBLE` result with a 0.5% gap is
close to the best possible, while a 40% gap says the solver has not narrowed things down yet.

## Explaining infeasibility

When a model has no solution, `INFEASIBLE` alone does not say why. Turn on
`explainInfeasibility(true)` and the solver runs once more on infeasible models, with its main
constraint groups labeled, and reports a set of groups that cannot all hold.

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

roster.status();                                 // INFEASIBLE
roster.diagnostics().infeasibilityReasons();
// [staffing: shift 'day' on 2026-07-06 needs 2 qualified employee(s)]
```

What gets labeled:

| Domain | Labeled groups |
|---|---|
| Scheduling | Staffing of each shift instance, role counts, `assign`/`forbid` locks, weekly maximum and minimum hours per employee, every rule per employee. |
| Job-shop | Release dates, hard deadlines, operation order within a job, maximum lags, precedences between jobs. |
| Cutting | "All pieces of type X must be cut" (1D), "piece X#n must be cut" (2D). |
| Packing | "Box X must be packed", each container's weight limit. |

Things to know:

- The set is **sufficient, not minimal**: removing all the named groups makes the model
  feasible, but a smaller subset might too.
- Constraints the library cannot label (machine no-overlap, setup sequencing, geometric
  non-overlap) are always assumed. If they alone make the model infeasible, the list is empty.
- It costs one extra single-worker solve, and only when the model is infeasible. Feasible
  models are solved once.
- Knapsack and booking models cannot be infeasible (items and appointments may be left out),
  and routing is not a CP-SAT model, so the option has no effect there.

## Exporting the model

`exportModel(Path)` writes the CP-SAT model before each search. See
[Solver knobs](solver-knobs.md#exporting-the-model).

## Search log

`verbose(true)` prints the solver's own progress log to standard output: presolve summary,
bound improvements, and which subsolver found each solution.

# Shift scheduling

Assign employees to shifts over a horizon of days, respecting skills, availability, labor
rules and staffing levels, at minimum cost.

Package `io.github.janglada.orkit.scheduling` · entry point `Optimize.scheduling()` · result `EmployeeRoster`

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.scheduling.EmployeeRoster;
import io.github.janglada.orkit.scheduling.ShiftAssignment;
import static io.github.janglada.orkit.scheduling.Goals.*;
import static io.github.janglada.orkit.scheduling.Rules.*;
import java.time.*;

EmployeeRoster roster = Optimize.scheduling()
        .horizon(LocalDate.of(2026, 7, 6), 14)
        .employee("Alice", e -> e.skills("RN").costPerHour(50).maxHoursPerWeek(40).prefersShift("Day"))
        .employee("Bob",   e -> e.skills("RN").costPerHour(48).maxHoursPerWeek(40))
        .shift("Day", s -> s.daily()
                .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                .needs(2).needsAtLeast(1).withSkill("RN")
                .category("DAY"))
        .shift("Night", s -> s.every(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
                .from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0))
                .needs(1).withSkill("RN").category("NIGHT"))
        .constrain(oneShiftPerEmployeePerDay())
        .constrain(minRestBetweenShifts(Duration.ofHours(11)))
        .constrain(forbidSequence("NIGHT", "DAY"))
        .prefer(balanceWorkloadAcrossEmployees())
        .prefer(honorEmployeePreferences())
        .goal(totalLaborCost())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

for (ShiftAssignment a : roster.assignments()) {
    System.out.printf("%s  %s  %s%n", a.date(), a.shift(), a.employee());
}
System.out.printf("Labor cost: %.2f%n", roster.laborCost());
```

## Shifts

A shift is declared once and expanded into one instance per date it occurs on.

| Method | Meaning |
|---|---|
| `daily()` | One instance every day of the horizon. |
| `every(DayOfWeek...)`, `on(DayOfWeek)` | One instance on every matching date. |
| `on(LocalDate)` | A single instance. |
| `from(LocalTime)`, `to(LocalTime)` | Start and end; `to` before `from` means an overnight shift. |
| `needs(n)` | Head-count required (hard, unless `needsAtLeast` lowers the floor). |
| `needsAtLeast(min)` | Hard minimum; each missing employee up to `needs` is penalized. |
| `withSkill(s)`, `withSkills(s...)` | Every assignee must hold all listed skills. |
| `role(skill, count)` | `count` of the assignees fill a role needing `skill`. |
| `category(c)` | Tag used by `forbidSequence`. |

Every instance reports the declared id in `ShiftAssignment.shift()` and is told apart by
`date()`. A recurrence with no occurrence in the horizon is rejected.

### Skills and roles

```java
.shift("ward", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
        .needs(4).withSkill("RN")       // four nurses...
        .role("CHARGE", 1))             // ...one of them qualified to be in charge
```

Each employee fills at most one role; the rest fill none. Role counts may not exceed the
shift's hard minimum head-count. `ShiftAssignment.role()` reports the role filled, or `null`.

## Employees

| Method | Meaning |
|---|---|
| `skills(String...)` | Skills held. |
| `costPerHour(int)` | Hourly cost, used by `totalLaborCost()`. |
| `availableOn(DayOfWeek...)` | Working days; empty means every day. |
| `unavailableOn(LocalDate...)`, `onHoliday(from, to)` | Blocked dates. |
| `maxHoursPerWeek(h)` | Cap on every window of 7 consecutive days. |
| `minHoursPerWeek(h)` | Hard floor in every full week from the horizon start. |
| `contractHoursPerWeek(h)` | Soft target in every full week; deviation is penalized. |
| `prefersShift(shiftId)` | A preference rewarded by `honorEmployeePreferences`. |

"Full week" means days 1 to 7, 8 to 14, and so on from the horizon start; a trailing partial
week is not constrained by the minimum or the contract.

## Locks

```java
.assign("Alice", "Day", LocalDate.of(2026, 7, 6))   // force one instance
.forbid("Bob", "Night")                             // never, on any instance
```

Without a date the lock applies to every instance. Assigning an ineligible employee, assigning
more people than `needs`, or both assigning and forbidding the same pair fails at build time.

## Rules

Hard constraints, added with `constrain(...)` from `Rules`:

| Rule | Meaning |
|---|---|
| `oneShiftPerEmployeePerDay()` | At most one shift per calendar day. |
| `minRestBetweenShifts(Duration)` | Minimum clock time between two shifts of one employee. |
| `maxConsecutiveWorkDays(n)` | At most `n` working days in a row. |
| `maxShiftsPerWeek(n)` | At most `n` shifts in any 7 consecutive days. |
| `maxConsecutiveNightShifts(n)` | At most `n` overnight shifts in a row. |
| `forbidSequence(from, to)` | No `to`-category shift on the day after a `from`-category shift. |

## Objective

`goal(Goals.totalLaborCost())` minimizes cost; without it the model is a pure feasibility
problem unless soft goals add terms. Soft goals, added with `prefer(...)`:

| Soft goal | Default weight | Effect |
|---|---|---|
| `balanceWorkloadAcrossEmployees(w)` | 10 | Penalizes each minute of spread between most and least loaded. |
| `honorEmployeePreferences(w)` | 5 | Rewards each satisfied preference. |
| `penalizeUnderstaffing(w)` | 100 | Penalizes each employee missing below a shift's target. Applied with the default weight whenever a shift declares `needsAtLeast`. |
| `penalizeContractDeviation(w)` | 1 | Penalizes each minute away from contract hours. Applied with the default weight whenever an employee declares contract hours. |

All weights share the unit of the cost term, `costPerHour × minutes`. See
[Modeling notes](../guide/modeling-notes.md#scheduling-weights) for how to size them.

## Reference

### `EmployeeRoster`

| Method | Returns |
|---|---|
| `assignments()` | Every `ShiftAssignment`. |
| `assignmentsOn(date)` | Assignments on a date. |
| `shiftsFor(employee)` | Shift ids the employee works, one per assignment. |
| `staffOf(shift)`, `staffOf(shift, date)` | Employees on a shift, across dates or on one. |
| `laborCost()` | Total cost in currency. |
| `hoursOf(employee)` | Hours worked over the horizon. |

`ShiftAssignment` has `employee()`, `shift()`, `date()`, `start()`, `end()`, `role()`,
`cost()` and `minutes()`.

!!! note "`objective()` versus `laborCost()`"
    `objective()` is the solver's weighted score: cost in `costPerHour × minutes` plus
    soft-goal penalties and rewards. Use `laborCost()` when you want money.

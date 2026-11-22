# Shift scheduling

**The problem in one sentence:** you have employees and shifts to fill over a number of days,
and you want to know who works when, following your rules and at the lowest cost.

A few words used on this page:

- The **horizon** is the period you plan: a start date and a number of days.
- A **shift** is a block of work with a start and end time, such as "Morning, 7:00 to 15:00".
  You declare it once, and it repeats on the days you choose.
- The **roster** is the answer: the list of who works which shift on which date.

Package `io.github.janglada.orkit.scheduling` · start with `Optimize.scheduling()` · result `EmployeeRoster`

## Quick start

A café is open every day and needs one person in the morning and one in the evening. Three
people work there, each at most 40 hours a week.

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.scheduling.EmployeeRoster;
import static io.github.janglada.orkit.scheduling.Goals.*;
import static io.github.janglada.orkit.scheduling.Rules.*;
import java.time.*;
import java.util.List;

LocalDate monday = LocalDate.of(2026, 7, 6);

EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 7)                                   // one week, Monday to Sunday
        .employee("Alice", e -> e.costPerHour(15).maxHoursPerWeek(40))
        .employee("Bob",   e -> e.costPerHour(14).maxHoursPerWeek(40))
        .employee("Carol", e -> e.costPerHour(16).maxHoursPerWeek(40))
        .shift("Morning", s -> s.daily()
                .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0)).needs(1))
        .shift("Evening", s -> s.daily()
                .from(LocalTime.of(15, 0)).to(LocalTime.of(23, 0)).needs(1))
        .constrain(oneShiftPerEmployeePerDay())
        .constrain(minRestBetweenShifts(Duration.ofHours(11)))
        .goal(totalLaborCost())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

for (int d = 0; d < 7; d++) {
    LocalDate day = monday.plusDays(d);
    System.out.printf("%s %s  Morning: %-6s Evening: %s%n",
            day.getDayOfWeek().toString().substring(0, 3), day,
            roster.staffOf("Morning", day).get(0), roster.staffOf("Evening", day).get(0));
}
for (String name : List.of("Alice", "Bob", "Carol")) {
    System.out.printf("%s works %.0f hours%n", name, roster.hoursOf(name));
}
System.out.println("Labor cost: " + roster.laborCost());
```

Prints something like:

```text
MON 2026-07-06  Morning: Carol  Evening: Bob
TUE 2026-07-07  Morning: Carol  Evening: Alice
WED 2026-07-08  Morning: Carol  Evening: Bob
THU 2026-07-09  Morning: Carol  Evening: Alice
FRI 2026-07-10  Morning: Bob    Evening: Alice
SAT 2026-07-11  Morning: Bob    Evening: Alice
SUN 2026-07-12  Morning: Bob    Evening: Alice
Alice works 40 hours
Bob works 40 hours
Carol works 32 hours
Labor cost: 1672.0
```

Alice and Bob are the cheapest, so they work their full 40 hours. Carol covers the rest.
Several rosters cost exactly 1672, so the names on a given day may differ on your machine.
The cost will not.

The model has five parts, and every scheduling model follows the same outline:

1. `horizon(...)`: the period to plan.
2. `employee(...)`: the people.
3. `shift(...)`: the work to cover.
4. `constrain(...)`: rules that must always hold.
5. `goal(...)` and `prefer(...)`: what makes one roster better than another.

## Shifts

You declare a shift once. orkit creates one copy for each date it falls on.

### When a shift happens

| Method | The shift happens |
|---|---|
| `daily()` | Every day of the horizon. |
| `every(DayOfWeek...)` | On the weekdays you list, every week. |
| `on(DayOfWeek)` | On one weekday, every week. |
| `on(LocalDate)` | On one exact date only. |

```java
.shift("Morning",   s -> s.daily()...)
.shift("Weekend",   s -> s.every(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)...)
.shift("Inventory", s -> s.on(DayOfWeek.MONDAY)...)
.shift("Open-day",  s -> s.on(LocalDate.of(2026, 7, 18))...)
```

A shift that never falls inside the horizon (a Saturday shift in a Monday-to-Friday plan) is
rejected, because it is almost certainly a mistake:
`Shift 'Sat' never occurs in the horizon [2026-07-06, +5 days)`.

### Start and end

```java
.shift("Day",   s -> s.daily().from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))...)
.shift("Night", s -> s.daily().from(LocalTime.of(23, 0)).to(LocalTime.of(7, 0))...)   // ends next morning
```

When the end time is earlier than the start time, the shift runs past midnight. The night
shift above lasts 8 hours and belongs to the day on which it starts.

### How many people

| Method | In plain words |
|---|---|
| `needs(n)` | The shift needs exactly `n` people. |
| `needsAtLeast(min)` | Fewer than `needs` is allowed, down to `min`. Each missing person counts against the roster. |

**Example: one is enough to open, two would be better.** Bob is off on Tuesday.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 2)
        .employee("Alice", e -> {})
        .employee("Bob",   e -> e.unavailableOn(monday.plusDays(1)))    // off on Tuesday
        .shift("Counter", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0))
                .needs(2)             // we would like two people
                .needsAtLeast(1))     // but one is enough to open
        .solver().solve();

System.out.println("Monday:  " + roster.staffOf("Counter", monday));
System.out.println("Tuesday: " + roster.staffOf("Counter", monday.plusDays(1)));
```

```text
Monday:  [Alice, Bob]
Tuesday: [Alice]
```

Without `needsAtLeast(1)`, the same model has no answer and the status is `INFEASIBLE`: on
Tuesday there is only one person for a shift that needs two.

(`e -> {}` means "an employee with no special settings".)

### Skills

A *skill* is any label you choose: a qualification, a license, a language. A shift can
require one, and only employees who have it may work that shift.

```java
.employee("Ana", e -> e.skills("RN"))                  // a registered nurse
.employee("Dev", e -> e.skills("LPN"))                 // a practical nurse
.shift("Ward", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
        .needs(2).withSkill("RN"))                     // both people must be registered nurses
```

`withSkills("RN", "ICU")` requires every person on the shift to have **all** the listed
skills.

### Roles

Sometimes only some of the people on a shift need an extra skill. That is a *role*.

**Example:** a ward needs three registered nurses, and one of them must be able to take
charge.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 1)
        .employee("Ana",   e -> e.skills("RN", "CHARGE"))
        .employee("Ben",   e -> e.skills("RN"))
        .employee("Chloe", e -> e.skills("RN"))
        .employee("Dev",   e -> e.skills("LPN"))            // not a registered nurse
        .shift("Ward", s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0))
                .needs(3).withSkill("RN")       // three registered nurses...
                .role("CHARGE", 1))             // ...one of them able to take charge
        .solver().solve();

for (ShiftAssignment a : roster.assignments()) {
    System.out.println(a.employee() + (a.role() != null ? " (" + a.role() + ")" : ""));
}
```

```text
Ana (CHARGE)
Ben
Chloe
```

`role("CHARGE", 1)` means "one of the people on this shift fills the CHARGE role, and must
have the CHARGE skill". A person fills at most one role. `a.role()` is `null` for people who
fill none.

### All shift settings

| Method | Meaning |
|---|---|
| `daily()`, `every(...)`, `on(...)` | When the shift happens. |
| `from(LocalTime)`, `to(LocalTime)` | Start and end time of day. |
| `needs(n)` | Number of people wanted. |
| `needsAtLeast(min)` | Smallest number allowed. |
| `withSkill(s)`, `withSkills(s...)` | Skills that everyone on the shift must have. |
| `role(skill, count)` | `count` of the people fill a role that needs `skill`. |
| `category(c)` | A label used by the rule [`forbidSequence`](#rules). |

## Employees

### Cost

`costPerHour(c)` is what an hour of this person's work costs. It is used by the goal
`totalLaborCost()`.

### Days off and holidays

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 7)
        .employee("Dana", e -> e.availableOn(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY))
        .employee("Eli",  e -> e.unavailableOn(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 8)))
        .employee("Fay",  e -> e.onHoliday(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10)))
        .shift("Desk", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0)).needs(1))
        .constrain(maxConsecutiveWorkDays(2))
        .solver().solve();

for (int d = 0; d < 7; d++) {
    LocalDate day = monday.plusDays(d);
    System.out.printf("%-9s %s%n", day.getDayOfWeek(), roster.staffOf("Desk", day));
}
```

Prints something like:

```text
MONDAY    [Dana]
TUESDAY   [Eli]
WEDNESDAY [Fay]
THURSDAY  [Eli]
FRIDAY    [Eli]
SATURDAY  [Fay]
SUNDAY    [Fay]
```

| Method | In plain words |
|---|---|
| `availableOn(DayOfWeek...)` | Works only on these weekdays. If you never call it, every weekday is fine. |
| `unavailableOn(LocalDate...)` | Cannot work on these exact dates. |
| `onHoliday(from, to)` | Away from `from` to `to`, both dates included. |

### Hours per week

| Method | Kind | In plain words |
|---|---|---|
| `maxHoursPerWeek(h)` | hard | Never more than `h` hours in any 7 days in a row. |
| `minHoursPerWeek(h)` | hard | At least `h` hours in each full week. |
| `contractHoursPerWeek(h)` | soft | Aim for `h` hours in each full week. Working more or less is allowed but counts against the roster. |

*Hard* means "never broken". *Soft* means "followed when possible".

**Example: contract hours.** One shift a day for a week makes 56 hours. A full-timer has a
40-hour contract, a part-timer a 16-hour one.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 7)
        .employee("Full-timer", e -> e.contractHoursPerWeek(40))
        .employee("Part-timer", e -> e.contractHoursPerWeek(16).minHoursPerWeek(8))
        .shift("Day", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0)).needs(1))
        .constrain(oneShiftPerEmployeePerDay())
        .solver().solve();

System.out.printf("Full-timer: %.0f h, Part-timer: %.0f h%n",
        roster.hoursOf("Full-timer"), roster.hoursOf("Part-timer"));
```

```text
Full-timer: 40 h, Part-timer: 16 h
```

What counts as a week:

- For `maxHoursPerWeek`, **any** 7 days in a row. Wednesday to the next Tuesday counts too.
- For `minHoursPerWeek` and `contractHoursPerWeek`, weeks are counted from the first day of
  the horizon: days 1 to 7, days 8 to 14, and so on. If the horizon ends with an incomplete
  week, those last days are not checked.

### Preferences

`prefersShift(shiftId)` says that an employee likes a shift. It does nothing on its own. Add
`prefer(honorEmployeePreferences())` to make the solver take it into account.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 5)
        .employee("Early-bird", e -> e.prefersShift("Morning"))
        .employee("Night-owl",  e -> e.prefersShift("Evening"))
        .shift("Morning", s -> s.daily().from(LocalTime.of(6, 0)).to(LocalTime.of(14, 0)).needs(1))
        .shift("Evening", s -> s.daily().from(LocalTime.of(14, 0)).to(LocalTime.of(22, 0)).needs(1))
        .constrain(oneShiftPerEmployeePerDay())
        .prefer(honorEmployeePreferences())
        .solver().solve();

System.out.println("Morning: " + roster.staffOf("Morning"));
System.out.println("Evening: " + roster.staffOf("Evening"));
```

```text
Morning: [Early-bird, Early-bird, Early-bird, Early-bird, Early-bird]
Evening: [Night-owl, Night-owl, Night-owl, Night-owl, Night-owl]
```

Everyone gets the shift they like, all five days.

## Fixing or blocking an assignment

Sometimes part of the roster is already decided: someone was promised a shift, or someone
must never work nights. Use `assign` and `forbid`.

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 3)
        .employee("Alice", e -> {})
        .employee("Bob",   e -> {})
        .employee("Carol", e -> {})
        .shift("Day",   s -> s.daily().from(LocalTime.of(8, 0)).to(LocalTime.of(16, 0)).needs(1))
        .shift("Night", s -> s.daily().from(LocalTime.of(22, 0)).to(LocalTime.of(6, 0)).needs(1))
        .constrain(oneShiftPerEmployeePerDay())
        .assign("Alice", "Day", monday)               // Alice works the Day shift on Monday
        .forbid("Bob", "Night", monday.plusDays(1))   // Bob cannot work Tuesday night
        .forbid("Alice", "Night")                     // Alice never works nights
        .solver().solve();
```

| Call | In plain words |
|---|---|
| `assign(employee, shift, date)` | This person works this shift on this date. |
| `assign(employee, shift)` | This person works this shift every time it happens. |
| `forbid(employee, shift, date)` | This person does not work this shift on this date. |
| `forbid(employee, shift)` | This person never works this shift. |

Contradictions are caught when the problem is built. For example, assigning and forbidding
the same person on the same shift gives:

```text
ModelException: 'Alice' is both assigned to and forbidden from 'Day' on 2026-07-06.
```

So does assigning someone who lacks a required skill, or assigning more people than the
shift `needs`.

## Rules

Rules are things that must hold for **every** employee. Add them with `constrain(...)`. They
come from the `Rules` class.

| Rule | In plain words |
|---|---|
| `oneShiftPerEmployeePerDay()` | Nobody works two shifts on the same calendar day. |
| `minRestBetweenShifts(Duration)` | At least this much time between the end of one shift and the start of the next. |
| `maxConsecutiveWorkDays(n)` | Nobody works more than `n` days in a row. |
| `maxShiftsPerWeek(n)` | Nobody works more than `n` shifts in any 7 days in a row. |
| `maxConsecutiveNightShifts(n)` | Nobody works more than `n` overnight shifts in a row. |
| `forbidSequence(from, to)` | After a shift of category `from`, no shift of category `to` on the next day. |

!!! warning "Almost every roster needs `oneShiftPerEmployeePerDay()`"
    Without any rule, nothing stops the solver from giving one person two shifts on the same
    day, even two that overlap in time. Add `oneShiftPerEmployeePerDay()`, a
    `minRestBetweenShifts(...)`, or both.

**Example: a day-and-night rota for four people.**

```java
EmployeeRoster roster = Optimize.scheduling()
        .horizon(monday, 7)
        .employee("Ana", e -> {})
        .employee("Ben", e -> {})
        .employee("Cy",  e -> {})
        .employee("Di",  e -> {})
        .shift("Day", s -> s.daily().from(LocalTime.of(7, 0)).to(LocalTime.of(19, 0))
                .needs(1).category("DAY"))
        .shift("Night", s -> s.daily().from(LocalTime.of(19, 0)).to(LocalTime.of(7, 0))
                .needs(1).category("NIGHT"))
        .constrain(oneShiftPerEmployeePerDay())
        .constrain(forbidSequence("NIGHT", "DAY"))      // no day shift right after a night
        .constrain(maxConsecutiveNightShifts(2))
        .constrain(maxConsecutiveWorkDays(4))
        .constrain(maxShiftsPerWeek(4))
        .constrain(minRestBetweenShifts(Duration.ofHours(11)))
        .solver().solve();

for (int d = 0; d < 7; d++) {
    LocalDate day = monday.plusDays(d);
    System.out.printf("%s  Day: %-4s Night: %s%n", day,
            roster.staffOf("Day", day).get(0), roster.staffOf("Night", day).get(0));
}
```

Prints something like:

```text
2026-07-06  Day: Ben  Night: Cy
2026-07-07  Day: Di   Night: Ben
2026-07-08  Day: Cy   Night: Di
2026-07-09  Day: Ana  Night: Di
2026-07-10  Day: Ana  Night: Ben
2026-07-11  Day: Di   Night: Ana
2026-07-12  Day: Cy   Night: Ben
```

Look at any night: the person who worked it never has the day shift the next morning.

`forbidSequence` works with *categories*. A category is a label you put on shifts with
`category(...)`, so that one rule can cover several shifts ("Night-A" and "Night-B" can both
be in the category `NIGHT`).

## What makes a roster better

By default the solver returns **any** roster that follows the rules. To get a good one, say
what "good" means.

### Cost

```java
.goal(totalLaborCost())
```

Makes the roster as cheap as possible, using each person's `costPerHour`.

### Softer wishes

Add any of these with `prefer(...)`. They come from the `Goals` class.

| Wish | In plain words |
|---|---|
| `balanceWorkloadAcrossEmployees()` | Spread the hours evenly between people. |
| `honorEmployeePreferences()` | Give people the shifts they said they like. |
| `penalizeUnderstaffing()` | Avoid shifts with fewer people than `needs`. Added for you when a shift uses `needsAtLeast`. |
| `penalizeContractDeviation()` | Keep people close to their contract hours. Added for you when an employee has contract hours. |

**Example: fair workloads.** Two people cost the same, and there is one shift a day for six
days.

```java
SchedulingBuilder b = Optimize.scheduling()
        .horizon(monday, 6)
        .employee("Alice", e -> e.costPerHour(20))
        .employee("Bob",   e -> e.costPerHour(20))
        .shift("Day", s -> s.daily().from(LocalTime.of(9, 0)).to(LocalTime.of(17, 0)).needs(1))
        .goal(totalLaborCost());
```

| | Alice | Bob |
|---|---|---|
| As written above | 0 h | 48 h |
| With `.prefer(balanceWorkloadAcrossEmployees())` added | 24 h | 24 h |

Without the wish, every split costs the same, so the solver has no reason to be fair. It may
give all six shifts to one person.

### When wishes compete

Each wish has a *weight*: a number that says how much it counts. You can pass your own, for
example `honorEmployeePreferences(10_000)`.

Cost counts in large numbers, so the default weights are small next to it. With the
defaults, a wish only decides between rosters that cost the same. To let a wish win against
cost, raise its weight. [Modeling notes](../guide/modeling-notes.md#scheduling-making-preferences-matter)
explains how to choose the number, with a worked example.

## Reading the roster

```java
roster.assignments();                    // every (employee, shift, date) in the roster
roster.assignmentsOn(monday);            // only those on one date
roster.staffOf("Morning", monday);       // [Carol]: who works that shift that day
roster.staffOf("Morning");               // one name per day worked, across the whole horizon
roster.shiftsFor("Carol");               // [Morning, Morning, Morning, Morning]
roster.hoursOf("Carol");                 // 32.0
roster.laborCost();                      // 1672.0
```

Each `ShiftAssignment` holds one person on one shift on one date:

```java
ShiftAssignment a = roster.assignments().get(0);

a.employee();   // "Alice"
a.shift();      // "Evening": the name you gave the shift
a.date();       // 2026-07-07
a.start();      // 15:00
a.end();        // 23:00
a.minutes();    // 480
a.cost();       // 120.0: 8 hours at 15 per hour
a.role();       // null, or the role this person fills
```

!!! note "`objective()` is not money"
    `roster.objective()` is the solver's internal score: cost counted per minute, plus and
    minus the wishes. Use `roster.laborCost()` when you want the cost in money.

## When there is no roster

If the status is `INFEASIBLE`, the rules cannot all be followed: not enough people, not
enough qualified people, or limits that are too tight. Ask the solver which rules clash:

```java
EmployeeRoster roster = builder.solver().explainInfeasibility(true).solve();
roster.diagnostics().infeasibilityReasons().forEach(System.out::println);
```

```text
staffing: shift 'day' on 2026-07-06 needs 2 qualified employee(s)
```

See [Diagnostics](../guide/diagnostics.md#finding-out-why-there-is-no-plan) for full examples.
A common fix is to replace `needs(n)` with `needs(n).needsAtLeast(m)`, so that a shift may
run short instead of making the whole roster impossible.

## A fuller example

Two nurses, two weeks, day shifts every day and night shifts on Fridays and Saturdays.

```java
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

System.out.println(roster.status() + ", " + roster.assignments().size()
        + " assignments, labor cost " + roster.laborCost());
```

```text
OPTIMAL, 18 assignments, labor cost 7056.0
```

There are 14 day shifts and 4 night shifts, and the roster has exactly 18 assignments. So
every day shift runs with one nurse instead of the two that were wanted. A second nurse on a
shift would cost far more than the small default penalty for being one short.

If staffing matters more than cost, raise that penalty with
`.prefer(penalizeUnderstaffing(50_000))`. The same model then returns 20 assignments at a
labor cost of 7840: two more shifts are fully staffed, which is all that two nurses can do
within 40 hours a week each.

## Reference

### `SchedulingBuilder`

| Method | Purpose |
|---|---|
| `horizon(LocalDate start, int days)` | The period to plan. |
| `employee(id, config)` | Declare a person. |
| `shift(id, config)` | Declare a shift. |
| `assign(employee, shift[, date])` | Fix an assignment. |
| `forbid(employee, shift[, date])` | Block an assignment. |
| `constrain(Rule)` | Add a rule from `Rules`. |
| `prefer(SoftGoal)` | Add a wish from `Goals`. |
| `goal(SchedulingGoal)` | Set the main goal: `Goals.totalLaborCost()`. |
| `build()`, `solver()` | Freeze the problem, or go straight to solving. |

### `EmployeeConfig`

| Method | Purpose |
|---|---|
| `skills(String...)` | Skills this person has. |
| `costPerHour(int)` | Cost of one hour of work. |
| `availableOn(DayOfWeek...)` | Weekdays this person works. |
| `unavailableOn(LocalDate...)` | Dates this person cannot work. |
| `onHoliday(from, to)` | A range of dates off, both ends included. |
| `maxHoursPerWeek(int)` | Most hours in any 7 days in a row. |
| `minHoursPerWeek(int)` | Fewest hours in each full week. |
| `contractHoursPerWeek(int)` | Hours to aim for in each full week. |
| `prefersShift(shiftId)` | A shift this person likes. |

### `EmployeeRoster`

| Method | Returns |
|---|---|
| `assignments()` | Every `ShiftAssignment`. |
| `assignmentsOn(date)` | The assignments on one date. |
| `shiftsFor(employee)` | Names of the shifts the person works, one entry per assignment. |
| `staffOf(shift)` | Names of the people on a shift, one entry per assignment. |
| `staffOf(shift, date)` | Names of the people on a shift on one date. |
| `laborCost()` | Total cost, in money. |
| `hoursOf(employee)` | Hours the person works over the whole horizon. |

### Default weights

| Wish | Default weight | Counted per |
|---|---|---|
| `balanceWorkloadAcrossEmployees(w)` | 10 | minute of difference between the busiest and the least busy person |
| `honorEmployeePreferences(w)` | 5 | preference that is satisfied |
| `penalizeUnderstaffing(w)` | 100 | missing person on a shift |
| `penalizeContractDeviation(w)` | 1 | minute above or below contract hours |

The roster also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

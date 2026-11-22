# Resource booking

**The problem in one sentence:** you have appointments to place on the calendars of people,
rooms or equipment, and you want as many as possible to get a slot, without anyone being
booked twice.

A few words used on this page:

- A **resource** is anything that an appointment needs and that can be busy: a doctor, a
  meeting room, a machine.
- An **appointment** is a block of time with a fixed length that needs one or more
  resources.
- The **horizon** is the period you plan: a start date and a number of days.

The solver picks the start time of each appointment, and which resource it uses when there
is a choice. An appointment that cannot be placed is left out and reported.

Package `io.github.janglada.orkit.booking` · start with `Optimize.booking()` · result `BookingSolution`

## Quick start

One meeting room, free from 9:00 to 12:00 on Monday, and four requests for it.

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.booking.*;
import java.time.*;

LocalDate monday = LocalDate.of(2026, 7, 6);

BookingSolution plan = Optimize.booking()
        .horizon(monday, 1)                                         // just Monday
        .resource("Room-1", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
        .appointment("Team-sync", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("Room-1"))
        .appointment("Interview", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("Room-1"))
        .appointment("Workshop",  a -> a.duration(Duration.ofMinutes(90)).requiresAnyOf("Room-1"))
        .appointment("Training",  a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("Room-1"))
        .solver().solve();

for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("%-9s %s to %s in %s%n",
            a.appointmentId(), a.start().toLocalTime(), a.end().toLocalTime(), a.resourceId());
}
System.out.println("No slot for: " + plan.unscheduled());
```

Prints:

```text
Team-sync 09:00 to 09:30 in Room-1
Interview 09:30 to 10:30 in Room-1
Workshop  10:30 to 12:00 in Room-1
No slot for: [Training]
```

The four requests add up to four hours, and the room is free for three. Three of them fit,
and the fourth is reported in `unscheduled()`.

Two things are worth noticing:

- **There is always an answer.** Appointments that do not fit are left out. A booking model
  is never `INFEASIBLE`.
- **Appointments are placed as early as possible.** When several plans are equally good, the
  solver picks the one with the earliest start times.

## The horizon

```java
.horizon(LocalDate.of(2026, 7, 6), 5)      // Monday 6 July to Friday 10 July
```

The horizon starts at midnight on the first day and covers the number of days you give.
Everything must happen inside it. Times are exact to the minute.

You may call `horizon(...)` anywhere in the chain, even last.

## Resources: when they are free

A resource is free during its *availability windows*. There are four ways to declare them,
and you can combine them.

```java
BookingSolution plan = Optimize.booking()
        .horizon(monday, 5)                                           // Monday to Friday
        .resource("Dentist", r -> r
                .availableDaily(LocalTime.of(9, 0), LocalTime.of(13, 0))                  // every morning
                .available(DayOfWeek.TUESDAY, LocalTime.of(15, 0), LocalTime.of(19, 0))   // plus Tuesday afternoon
                .available(new DayOfWeek[]{DayOfWeek.THURSDAY, DayOfWeek.FRIDAY},
                        LocalTime.of(14, 0), LocalTime.of(16, 0)))                        // plus Thu and Fri 14-16
        .resource("X-ray", r -> r
                .available(new TimeSlot(                                                  // one exact window
                        monday.plusDays(2).atTime(10, 0),
                        monday.plusDays(2).atTime(12, 0))))
        .resource("Waiting-room", r -> {})                                                // always free
        .appointment("Scan", a -> a.duration(Duration.ofMinutes(45)).requiresAnyOf("X-ray"))
        .appointment("Late-checkup", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("Dentist")
                .earliest(monday.plusDays(1).atTime(16, 0)))
        .solver().solve();

for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("%s -> %s on %s, %s-%s%n", a.appointmentId(), a.resourceIds(),
            a.start().toLocalDate(), a.start().toLocalTime(), a.end().toLocalTime());
}
```

```text
Scan -> [X-ray] on 2026-07-08, 10:00-10:45
Late-checkup -> [Dentist] on 2026-07-07, 16:00-16:30
```

| Method | The resource is free |
|---|---|
| `availableDaily(from, to)` | Every day of the horizon, between these times. |
| `available(DayOfWeek, from, to)` | On that weekday, every week. |
| `available(DayOfWeek[], from, to)` | On each of those weekdays. |
| `available(TimeSlot)` | During one exact period, given as two date-times. |

Things to know:

- An appointment must fit **completely inside one window**. A 60-minute appointment cannot
  start in a morning window and finish in an afternoon one.
- Windows that overlap or touch are joined into one. `9:00 to 12:00` plus `12:00 to 15:00`
  behaves like `9:00 to 15:00`.
- A resource with **no** availability declared is free during the whole horizon, day and
  night.

## Appointments

### How long, and with what

```java
.appointment("Checkup", a -> a
        .duration(Duration.ofMinutes(30))           // required, at least one minute
        .requiresAnyOf("Dr-Chen", "Dr-Patel"))      // either doctor will do
```

`requiresAnyOf` lists the resources that can do the job. The solver picks **one** of them.
With a single name, there is no choice.

### Earliest and latest

By default an appointment may be placed anywhere in the horizon. Narrow that down with
`earliest` (do not start before) and `latest` (be finished by).

```java
BookingSolution plan = Optimize.booking()
        .horizon(monday, 3)
        .resource("Mechanic", r -> r.availableDaily(LocalTime.of(8, 0), LocalTime.of(17, 0)))
        .appointment("Oil-change", a -> a.duration(Duration.ofMinutes(45)).requiresAnyOf("Mechanic")
                .earliest(monday.plusDays(1).atTime(14, 0)))     // the car arrives Tuesday at 14:00
        .appointment("Tyres", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("Mechanic")
                .latest(monday.atTime(10, 0)))                    // must be finished by Monday 10:00
        .appointment("Inspection", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("Mechanic")
                .earliest(monday.plusDays(2).atTime(8, 0))
                .latest(monday.plusDays(2).atTime(12, 0)))        // some time on Wednesday morning
        .solver().solve();

for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("%-10s %s %s to %s%n", a.appointmentId(),
            a.start().getDayOfWeek(), a.start().toLocalTime(), a.end().toLocalTime());
}
```

```text
Oil-change TUESDAY 14:00 to 14:45
Tyres      MONDAY 08:00 to 09:00
Inspection WEDNESDAY 08:00 to 10:00
```

### Time between appointments

A *buffer* is time that the resource stays blocked **after** an appointment: cleaning a room,
writing up notes, letting a machine cool down.

```java
BookingSolution plan = Optimize.booking()
        .horizon(monday, 1)
        .resource("Chair", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
        .appointment("Patient-1", a -> a.duration(Duration.ofMinutes(30))
                .buffer(Duration.ofMinutes(15))            // 15 minutes to clean the chair
                .requiresAnyOf("Chair"))
        .appointment("Patient-2", a -> a.duration(Duration.ofMinutes(30))
                .buffer(Duration.ofMinutes(15))
                .requiresAnyOf("Chair"))
        .solver().solve();

for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("%s %s to %s%n",
            a.appointmentId(), a.start().toLocalTime(), a.end().toLocalTime());
}
```

```text
Patient-1 09:00 to 09:30
Patient-2 09:45 to 10:15
```

The second patient starts at 9:45, not 9:30. The appointment itself still ends at 9:30: the
buffer is not part of `end()`.

The buffer only keeps other appointments away. It is allowed to run past the end of the
resource's window: cleaning up after the last appointment of the day does not need to fit in
opening hours.

### Several resources at once

Some appointments need more than one thing at the same time: a surgeon **and** an operating
theatre **and** an anesthetist.

```java
BookingSolution plan = Optimize.booking()
        .horizon(monday, 1)
        .resource("Dr-Chen",     r -> r.availableDaily(LocalTime.of(8, 0), LocalTime.of(12, 0)))
        .resource("Dr-Patel",    r -> r.availableDaily(LocalTime.of(10, 0), LocalTime.of(18, 0)))
        .resource("Theatre-1",   r -> r.availableDaily(LocalTime.of(8, 0), LocalTime.of(18, 0)))
        .resource("Theatre-2",   r -> r.availableDaily(LocalTime.of(8, 0), LocalTime.of(18, 0)))
        .resource("Anesthetist", r -> r.availableDaily(LocalTime.of(11, 0), LocalTime.of(15, 0)))
        .appointment("Surgery", a -> a.duration(Duration.ofHours(2))
                .requiresAnyOf("Dr-Chen", "Dr-Patel")        // one surgeon
                .alsoRequiresAnyOf("Theatre-1", "Theatre-2") // one theatre
                .requiresAllOf("Anesthetist"))               // and the anesthetist
        .solver().solve();

AppointmentAssignment s = plan.assignments().get(0);
System.out.println(s.start().toLocalTime() + " to " + s.end().toLocalTime());
System.out.println("Booked: " + s.resourceIds());
```

```text
11:00 to 13:00
Booked: [Dr-Patel, Theatre-1, Anesthetist]
```

The anesthetist starts at 11:00, and Dr. Chen leaves at 12:00, too early for a two-hour
operation. So the solver books Dr. Patel, from 11:00.

| Method | In plain words |
|---|---|
| `requiresAnyOf(a, b, ...)` | The main need: **one** of these. |
| `alsoRequiresAnyOf(a, b, ...)` | A further need: **one** of these as well. Can be called several times. |
| `requiresAllOf(a, b, ...)` | **Every** one of these. Can be called several times. |

All the booked resources are busy for the same period, and each of them must be free then.
A resource may be listed in only one need of the same appointment.

In the result, `resourceIds()` lists everything that was booked, in the order of the needs.
`resourceId()` is the first of them.

## Goals: which appointments win

When not everything fits, something is left out. The goal decides what.

| Goal | In plain words | `objective()` returns |
|---|---|---|
| `Goals.maximizeScheduledAppointments()` (default) | Place as **many** appointments as possible. All count the same. | The number placed. |
| `Goals.minimizeUnscheduledPenalty()` | Leave out the **least important** ones. Each appointment has its own penalty for being left out. | The total penalty of those left out. |

**Example.** A room is free for two hours. A two-hour board meeting and two one-hour
meetings all want it.

```java
BookingSolution plan = Optimize.booking()
        .horizon(monday, 1)
        .resource("Room", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(11, 0)))   // two hours
        .appointment("Board-meeting", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("Room")
                .penaltyIfUnscheduled(100))
        .appointment("Coffee-chat", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("Room")
                .penaltyIfUnscheduled(1))
        .appointment("Stand-up", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("Room")
                .penaltyIfUnscheduled(1))
        .goal(goal)
        .solver().solve();
```

| `goal` | Placed | Left out |
|---|---|---|
| `Goals.maximizeScheduledAppointments()` | Coffee-chat, Stand-up | Board-meeting |
| `Goals.minimizeUnscheduledPenalty()` | Board-meeting | Coffee-chat, Stand-up |

The first goal counts heads: two meetings beat one. The second looks at importance: leaving
out the board meeting would cost 100, leaving out the other two costs only 2.

`penaltyIfUnscheduled` defaults to 1 and is only used by `minimizeUnscheduledPenalty()`.

### Early starts

Among plans that are equally good for the goal, the solver prefers the one where
appointments start earliest. This keeps the calendar compact and the result predictable.
`objective()` is not affected by it.

To turn this off, call `preferEarlyStarts(false)`. Appointments may then land anywhere in
their windows.

## Reading the result

```java
plan.assignments();                 // the appointments that got a slot, in declaration order
plan.unscheduled();                 // names of the appointments that did not
plan.assignmentsOn("Dr-Patel");     // everything booked on one resource
```

Each `AppointmentAssignment`:

```java
a.appointmentId();   // "Surgery"
a.start();           // 2026-07-06T11:00
a.end();             // 2026-07-06T13:00 (the buffer is not included)
a.duration();        // PT2H
a.resourceId();      // "Dr-Patel": the first resource booked
a.resourceIds();     // [Dr-Patel, Theatre-1, Anesthetist]
```

**Example: print one person's day.**

```java
for (AppointmentAssignment a : plan.assignmentsOn("Dr-Patel")) {
    System.out.println(a.start().toLocalTime() + "  " + a.appointmentId());
}
```

## A fuller example

Two doctors with different hours, a buffer, a choice of doctor, and a penalty:

```java
BookingSolution plan = Optimize.booking()
        .horizon(LocalDate.of(2026, 7, 6), 5)
        .resource("Dr-Chen", r -> r
                .availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0))
                .available(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0)))
        .resource("Dr-Patel", r -> r
                .available(new TimeSlot(
                        LocalDateTime.of(2026, 7, 6, 8, 0),
                        LocalDateTime.of(2026, 7, 6, 16, 0))))
        .appointment("A1", a -> a.duration(Duration.ofMinutes(30))
                .buffer(Duration.ofMinutes(10))
                .requiresAnyOf("Dr-Chen", "Dr-Patel"))
        .appointment("A2", a -> a.duration(Duration.ofMinutes(60))
                .requiresAnyOf("Dr-Chen")
                .earliest(LocalDateTime.of(2026, 7, 6, 10, 0))
                .penaltyIfUnscheduled(50))
        .goal(Goals.minimizeUnscheduledPenalty())
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .solve();

for (AppointmentAssignment a : plan.assignments()) {
    System.out.printf("%s -> %s at %s-%s%n",
            a.appointmentId(), a.resourceId(), a.start().toLocalTime(), a.end().toLocalTime());
}
System.out.println("Unscheduled: " + plan.unscheduled());
```

```text
A1 -> Dr-Patel at 08:00-08:30
A2 -> Dr-Chen at 10:00-11:00
Unscheduled: []
```

## Reference

### `BookingBuilder`

| Method | Purpose |
|---|---|
| `horizon(LocalDate start, int days)` | The period to plan. |
| `resource(id, config)` | Declare a resource and when it is free. |
| `appointment(id, config)` | Declare an appointment. |
| `goal(BookingGoal)` | Choose the goal. |
| `preferEarlyStarts(boolean)` | Prefer early start times among equally good plans. On by default. |
| `build()`, `solver()` | Freeze the problem, or go straight to solving. |

### `AppointmentConfig`

| Method | Default | Meaning |
|---|---|---|
| `duration(Duration)` | required | Length of the appointment, at least one minute. |
| `requiresAnyOf(ids...)` | | One of these resources. |
| `alsoRequiresAnyOf(ids...)` | | One of these too. |
| `requiresAllOf(ids...)` | | All of these. |
| `earliest(LocalDateTime)` | start of the horizon | Do not start before this. |
| `latest(LocalDateTime)` | end of the horizon | Be finished by this. |
| `buffer(Duration)` | zero | Time the resource stays blocked afterwards. |
| `penaltyIfUnscheduled(long)` | 1 | Cost of leaving this appointment out. |

### `BookingSolution`

| Method | Returns |
|---|---|
| `assignments()` | The appointments that got a slot, in declaration order. |
| `assignmentsOn(resource)` | The appointments that book this resource, for any of their needs. |
| `unscheduled()` | Names of the appointments that were left out. |

The result also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

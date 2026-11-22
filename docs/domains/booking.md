# Resource booking

Place appointments on the calendars of bookable resources (people, rooms, equipment) so that
no resource is double-booked and as many appointments as possible, or the most important ones,
get a slot.

Package `io.github.janglada.orkit.booking` · entry point `Optimize.booking()` · result `BookingSolution`

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.booking.*;
import java.time.*;

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

## Horizon

`horizon(start, days)` covers `days` days from midnight of `start`. All availability and time
bounds must fall inside it. Declarations may come in any order; wall-clock times are resolved
against the horizon when the problem is built. Resolution is one minute.

## Goals

| Goal | Behavior | `objective()` reports |
|---|---|---|
| `Goals.maximizeScheduledAppointments()` (default) | Schedule as many appointments as possible. | The number scheduled. |
| `Goals.minimizeUnscheduledPenalty()` | Minimize the sum of `penaltyIfUnscheduled` over appointments left out. | That penalty. |

Appointments are optional under both goals, so a booking model is never infeasible.

### Early starts

Among plans that are equally good for the goal, the one with the earliest start times wins, so
appointments do not land at arbitrary points of their windows. `objective()` always reports
the goal alone. Turn the tie-break off with `preferEarlyStarts(false)`.

## Resources

| Method | Meaning |
|---|---|
| `available(TimeSlot)` | An explicit window. |
| `available(DayOfWeek, from, to)` | A weekly window on one weekday. |
| `available(DayOfWeek[], from, to)` | The same on several weekdays. |
| `availableDaily(from, to)` | A window every day of the horizon. |

Windows may be combined; overlapping or touching ones are merged. An appointment must fit
entirely inside one window. A resource with no declared availability is available for the
whole horizon.

## Appointments

| Method | Default | Meaning |
|---|---|---|
| `duration(Duration)` | required | At least one minute. |
| `requiresAnyOf(ids...)` | | The main requirement: one of these resources. |
| `alsoRequiresAnyOf(ids...)` | | A further requirement: one of these too. Repeatable. |
| `requiresAllOf(ids...)` | | Each of these, as its own requirement. Repeatable. |
| `earliest(LocalDateTime)` | horizon start | Earliest start. |
| `latest(LocalDateTime)` | horizon end | Latest end. |
| `buffer(Duration)` | zero | Turnaround that keeps the resource blocked after the appointment. |
| `penaltyIfUnscheduled(long)` | 1 | Cost of leaving it out, under `minimizeUnscheduledPenalty()`. |

The buffer is not part of the reported `end()` and may spill past the resource's window or the
appointment's `latest`, but it never overlaps another appointment on that resource.

### Several resources at once

```java
.appointment("Surgery", a -> a.duration(Duration.ofHours(2))
        .requiresAnyOf("Dr-Chen", "Dr-Patel")        // one surgeon
        .alsoRequiresAnyOf("Theatre-1", "Theatre-2") // one theatre
        .requiresAllOf("Anesthetist"))               // and the anesthetist
```

When scheduled, the appointment books one resource from every requirement for the same time,
and each of them must be available. A resource may appear in only one requirement of an
appointment. `AppointmentAssignment.resourceIds()` lists the booked resources in requirement
order; `resourceId()` is the first.

## Reference

### `BookingSolution`

| Method | Returns |
|---|---|
| `assignments()` | Scheduled appointments, in declaration order. |
| `assignmentsOn(resource)` | Appointments that book the resource for any requirement. |
| `unscheduled()` | Ids of appointments left out. |

`AppointmentAssignment` has `appointmentId()`, `resourceId()`, `resourceIds()`, `start()`,
`end()` and `duration()`.

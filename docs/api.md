# API reference

The guide and the domain pages explain how to use orkit, with examples. This page is for
when you need the exact details of one class or method: every parameter, every return value,
every exception.

That detail lives in the Javadoc, which is generated from the source code and bundled with
this site.

[Open the Javadoc :material-open-in-new:](javadoc/index.html){ .md-button .md-button--primary target=_blank }

## Packages

| Package | Contents |
|---|---|
| [`io.github.janglada.orkit`](javadoc/io/github/janglada/orkit/package-summary.html) | `Optimize`, `Solver`, `CpSatSolver`, `Solution`, `SolveStatus`, `SolveDiagnostics`, `ModelException` |
| [`...orkit.jobshop`](javadoc/io/github/janglada/orkit/jobshop/package-summary.html) | `JobShopBuilder`, `JobConfig`, `OperationConfig`, `Goals`, `JobShopSchedule`, `ScheduledOperation`, `Gantt` |
| [`...orkit.scheduling`](javadoc/io/github/janglada/orkit/scheduling/package-summary.html) | `SchedulingBuilder`, `EmployeeConfig`, `ShiftConfig`, `Rules`, `Goals`, `EmployeeRoster`, `ShiftAssignment` |
| [`...orkit.routing`](javadoc/io/github/janglada/orkit/routing/package-summary.html) | `RoutingBuilder`, `VehicleConfig`, `StopConfig`, `DistanceSource`, `RoutingSolver`, `RoutingPlan`, `Route`, `Visit`, `DriverBreak` |
| [`...orkit.knapsack`](javadoc/io/github/janglada/orkit/knapsack/package-summary.html) | `MultipleKnapsackBuilder`, `BinConfig`, `ItemConfig`, `MultipleKnapsackSolution`, `BinAssignment` |
| [`...orkit.packing`](javadoc/io/github/janglada/orkit/packing/package-summary.html) | `PackingBuilder`, `ContainerConfig`, `BoxConfig`, `Orientation`, `PackingSolution`, `ContainerAssignment` |
| [`...orkit.booking`](javadoc/io/github/janglada/orkit/booking/package-summary.html) | `BookingBuilder`, `ResourceConfig`, `AppointmentConfig`, `TimeSlot`, `BookingSolution`, `AppointmentAssignment` |
| [`...orkit.cutting`](javadoc/io/github/janglada/orkit/cutting/package-summary.html) | `CuttingBuilder`, `StockConfig`, `PieceConfig`, `CuttingSolution`, `StockAssignment` |

Each domain package also has a `Goals` class with the goals you can choose from.

## What you can rely on

**Use only the packages listed above.** They are the supported API.

Packages whose name ends in `.internal` contain the code that talks to OR-Tools. They are
not meant to be used directly, they are left out of the Javadoc, and they may change at any
time without notice.

**The version is still `0.1.0-SNAPSHOT`.** That means the library is not finished: method
names and behavior can still change from one build to the next. Changes that could break
your code are listed in the [changelog](changelog.md).

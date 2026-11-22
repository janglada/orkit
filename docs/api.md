# API reference

The complete Javadoc for the public API is generated from the sources and bundled with this
site.

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

## Stability

The supported API is everything in the packages above. Packages ending in `.internal` hold the
OR-Tools modeling code; they are public only so the domain packages can reach them, are left
out of this reference, and may change without notice.

The project is at `0.1.0-SNAPSHOT`: the API can still change between builds. Breaking changes
are listed in the [changelog](changelog.md).

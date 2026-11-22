# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `RoutingSolver.cancel()`: stops a running routing search and keeps the best routes so far.
- `StopConfig.penaltyIfDropped(long)`: per-stop drop penalty that overrides the model-wide one.
- Build gates: compiler warnings are errors, JaCoCo coverage floor (97% lines, 90% branches),
  javadoc doclint, and sources/javadoc jars on `mvn verify`.
- GitHub Actions CI on Linux, macOS and Windows (JDK 21) plus Linux on JDK 25; Dependabot.
- Solve diagnostics: `Solution.diagnostics()`, `bestBound()` and `gap()` report the best proven
  bound, the relative optimality gap, and search branches and conflicts on every CP-SAT result,
  intermediate ones included.
- `CpSatSolver.explainInfeasibility(boolean)`: an infeasible model is solved once more with its
  main constraint groups labeled, and `SolveDiagnostics.infeasibilityReasons()` names a set of
  groups that cannot hold together (staffing, hours, rules and locks in scheduling; releases,
  deadlines, lags, job order and precedences in job-shop; required pieces in cutting; required
  boxes and weight limits in packing).
- `CpSatSolver.exportModel(Path)` writes the validated CP-SAT model (text or binary protobuf)
  before each search.
- Routing: named capacity dimensions (`VehicleConfig.capacity(String, long)`,
  `StopConfig.demand(String, long)`), reported per dimension in `Route.loads()` and
  `Visit.loadsAfter()`.
- Routing: `RoutingSolver.onSolution(Consumer)` and `RoutingSolver.warmStart(RoutingPlan)`.
- Routing: soft deadlines with `StopConfig.lateAfter(LocalTime, long)`, reported in
  `Visit.minutesLate()`, `Route.latenessPenalty()` and `RoutingPlan.totalLatenessPenalty()`.
- Routing: driver breaks with `VehicleConfig.breakBetween(LocalTime, LocalTime, Duration)`,
  reported in `Route.breaks()`.
- Cutting: `PieceConfig.rotatable(boolean)` for 2D pieces, `CuttingBuilder.kerf(long)` for the
  blade width, and `CuttingBuilder.guillotine(boolean)` for two-stage guillotine 2D layouts;
  `CutPiece.rotated()` reports a turned piece.
- Scheduling: `ShiftConfig.withSkills(String...)` for multi-skill shifts and
  `ShiftConfig.role(String, int)` for a skill mix, reported in `ShiftAssignment.role()`;
  `EmployeeConfig.minHoursPerWeek(int)` and `contractHoursPerWeek(int)` with
  `Goals.penalizeContractDeviation(long)`; `EmployeeRoster.laborCost()` (currency) and `hoursOf(String)`.
- Booking: appointments that need several resources at once with
  `AppointmentConfig.alsoRequiresAnyOf(String...)` and `requiresAllOf(String...)`, reported in
  `AppointmentAssignment.resourceIds()`; `BookingBuilder.preferEarlyStarts(boolean)`.
- Job-shop: `precedence(...)` between jobs or operations of different jobs (cycles rejected),
  `machine(String, int)` for machines that run several operations at once, and calendar time with
  `startingAt(LocalDateTime)` and `timeUnit(Duration)`, which enable `Duration`/`LocalDateTime`
  inputs and report `ScheduledOperation.startTime()/endTime()` and `JobShopSchedule.makespanDuration()`.
- Routing: per-vehicle `costPerMeter` and `costPerMinute` under `Goals.totalCost()`, reported in
  `Route.variableCost()` and `RoutingPlan.totalVariableCost()`.

### Changed
- **Breaking:** Maven coordinates are now `io.github.janglada:orkit` (version `0.1.0-SNAPSHOT`),
  and the root package moved from `opt` to `io.github.janglada.orkit`. Update imports from
  `opt.*` to `io.github.janglada.orkit.*`. The jar declares `Automatic-Module-Name: io.github.janglada.orkit`.
- Licensed under Apache License 2.0.
- CP-SAT `cancel()` now also applies while the model is still being built.
- A second concurrent `solve()` on the same solver throws `ModelException`.
- An `Error` thrown by an `onSolution` listener is rethrown as is instead of being wrapped.
- The default CP-SAT worker count is capped at the processors available to the JVM.
- Examples moved from the library jar to the test sources.
- **Breaking:** routing capacities and demands are `long`. `Route` and `Visit` gained components
  (`load` and `loadAfter` are `long`; `loads`, `loadsAfter`, `variableCost`, `latenessPenalty`,
  `breaks`, `minutesLate`), so code calling their canonical constructors must be updated.
- `Goals.totalCost()` now adds per-vehicle variable costs and lateness penalties; with the
  defaults (1 per meter, 0 per minute, no soft deadlines) it is unchanged.
- `Route.distanceMeters()` is the sum of matrix distances, independent of per-vehicle costs.
- Packing uses one position per box and one set of separation literals per pair, plus volume
  and cross-section relaxations and symmetry breaking: instances of 30 to 60 rotatable boxes now
  prove optimality where the previous model did not.
- **Breaking:** `ShiftAssignment` gained `role` and `cost`, `AppointmentAssignment` gained
  `resourceIds`, and `ScheduledOperation` gained `startTime` and `endTime`.
- Booking now places appointments at the earliest start among equally good solutions
  (`preferEarlyStarts`, on by default); `objective()` still reports the goal alone.
- Cutting 1D is now a count model per (piece type, roll) and 2D uses `NoOverlap2D` with symmetry
  breaking; both scale to much larger instances. 1D instance ids are assigned after the solve in
  roll order, so `P#1` is the first `P` on the first used roll, and dropped instances are the
  highest-numbered ones. **Breaking:** `CutPiece` gained a `rotated` component.
- The explicit `ortools-linux-x86-64` dependency was removed; `ortools-java` already brings the
  native libraries for every supported platform.

### Removed
- `SolveStatus.mapStatus(CpSolverStatus)`, which exposed an OR-Tools type in the public API.
- `Strategy.SWEEP`: OR-Tools rejects it without a sweep arranger, which Java cannot set, so every
  solve using it returned `MODEL_INVALID`.

### Fixed
- Zero or negative time limits are rejected instead of being passed to the solver.
- Inputs whose horizons, volumes, areas or weighted costs would overflow a 64-bit integer are
  rejected with `ModelException` instead of silently wrapping around.
- Routing: a depot at latitude/longitude (0, 0) is accepted; coordinates are validated as WGS84;
  distance and time matrices must be non-negative and are defensively copied; a search cancelled
  before finding any solution reports `UNKNOWN` instead of `INFEASIBLE`.
- Packing `requireSupport(true)` now requires every box to stand on the floor or rest flush on a
  supporting box; before, only footprint containment was enforced, so a box could hover.
- Routing `Goals.vehiclesUsed()` with droppable stops no longer drops servable stops to save a
  vehicle when the drop penalty is below the internal vehicle cost; stops are dropped only when
  no vehicle can serve them.

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

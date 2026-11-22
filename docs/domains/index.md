# Domains

Each domain has its own package under `io.github.janglada.orkit`, a builder obtained from
`Optimize`, a `Goals` factory, and a typed result.

| Domain | Entry point | Result | Backend |
|---|---|---|---|
| [Job-shop scheduling](jobshop.md) | `Optimize.jobShop()` | `JobShopSchedule` | CP-SAT |
| [Shift scheduling](scheduling.md) | `Optimize.scheduling()` | `EmployeeRoster` | CP-SAT |
| [Vehicle routing](routing.md) | `Optimize.vehicleRouting()` | `RoutingPlan` | OR-Tools routing |
| [Multiple knapsack](knapsack.md) | `Optimize.multipleKnapsack()` | `MultipleKnapsackSolution` | CP-SAT |
| [Container packing](packing.md) | `Optimize.packing()` | `PackingSolution` | CP-SAT |
| [Resource booking](booking.md) | `Optimize.booking()` | `BookingSolution` | CP-SAT |
| [Cutting stock](cutting.md) | `Optimize.cuttingStock()` | `CuttingSolution` | CP-SAT |

## Which one do I need?

- **Operations on machines, in a fixed order per job** → job-shop.
- **People on shifts over days or weeks** → shift scheduling.
- **Vehicles visiting locations** → vehicle routing.
- **Pick the most valuable items that fit by weight** → multiple knapsack.
- **Fit boxes into containers in 3D** → container packing.
- **Place appointments on calendars of people and rooms** → resource booking.
- **Cut required pieces out of stock rolls or sheets** → cutting stock.

All CP-SAT domains share the same [solver knobs](../guide/solver-knobs.md) and
[diagnostics](../guide/diagnostics.md).

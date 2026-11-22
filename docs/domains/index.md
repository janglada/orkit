# Domains

A *domain* is one kind of problem that orkit knows how to solve. There are seven. Each has
its own page with a quick start you can copy, one small example per feature, and a reference
table.

## Which one do I need?

Find the sentence that sounds like your problem:

| If your problem sounds like... | Use | Real-life examples |
|---|---|---|
| "Each **job** goes through several **machines** in a fixed order. When should each step run?" | [Job-shop scheduling](jobshop.md) | A machine shop, a print shop, a bakery with one oven, a lab with shared instruments. |
| "I have **people** and **shifts** to fill over days or weeks. Who works when?" | [Shift scheduling](scheduling.md) | Nurses in a hospital, staff in a shop or call center, security guards. |
| "My **vehicles** must visit many **places**. Who goes where, in what order?" | [Vehicle routing](routing.md) | Parcel delivery, technicians visiting customers, waste collection. |
| "I have more **items** than fit. Which ones do I take to get the most **value**?" | [Multiple knapsack](knapsack.md) | Loading trucks by weight, choosing projects within a budget. |
| "I must fit **boxes** into **containers**, and I need to know where each box goes." | [Container packing](packing.md) | Loading a shipping container, a van or a pallet. |
| "I have **appointments** to place on the calendars of **people and rooms**." | [Resource booking](booking.md) | A clinic, a repair shop, meeting rooms. |
| "I must cut **pieces** out of long **rolls** or large **sheets** with little waste." | [Cutting stock](cutting.md) | Steel bars, timber, paper rolls, glass or metal sheets. |

### Two that are easy to mix up

**Knapsack or packing?** Knapsack only looks at one number per item, such as weight: "do
these items fit, in total?" Packing looks at the real shape: "where exactly does each box go,
and does it physically fit?" Use knapsack when only the total matters, packing when
dimensions matter.

**Job-shop or booking?** In job-shop, each job is a chain of steps and the solver decides
when everything runs, as early as possible. In booking, each appointment is a single block
that needs certain people or rooms, and each of those has opening hours. Use job-shop for
production, booking for calendars.

## At a glance

| Domain | Start with | You get back | Engine |
|---|---|---|---|
| [Job-shop scheduling](jobshop.md) | `Optimize.jobShop()` | `JobShopSchedule` | CP-SAT |
| [Shift scheduling](scheduling.md) | `Optimize.scheduling()` | `EmployeeRoster` | CP-SAT |
| [Vehicle routing](routing.md) | `Optimize.vehicleRouting()` | `RoutingPlan` | OR-Tools routing |
| [Multiple knapsack](knapsack.md) | `Optimize.multipleKnapsack()` | `MultipleKnapsackSolution` | CP-SAT |
| [Container packing](packing.md) | `Optimize.packing()` | `PackingSolution` | CP-SAT |
| [Resource booking](booking.md) | `Optimize.booking()` | `BookingSolution` | CP-SAT |
| [Cutting stock](cutting.md) | `Optimize.cuttingStock()` | `CuttingSolution` | CP-SAT |

The *engine* is the part of OR-Tools that does the search. You do not need to know it to use
a domain. It matters in two places only:

- The six CP-SAT domains share the same [solver settings](../guide/solver-knobs.md) and
  [diagnostics](../guide/diagnostics.md), and can prove that a plan is the best possible.
- Routing has its own settings and returns very good plans without such a proof.

Each domain lives in its own Java package under `io.github.janglada.orkit`, for example
`io.github.janglada.orkit.jobshop`.

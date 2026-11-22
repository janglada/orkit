# Glossary

Plain-language meanings of the terms used on this site, in alphabetical order.

**Bound**
:   A limit that the solver has proven no plan can beat. If you minimize and the bound is
    1073, no plan scores below 1073. See [Diagnostics](diagnostics.md#the-bound).

**Buffer** (booking)
:   Time after an appointment during which the resource stays blocked, for example to clean
    a room. The appointment itself does not get longer.

**Builder**
:   The object you describe your problem with. You get one from `Optimize`, for example
    `Optimize.jobShop()`.

**Capacity**
:   How much something can hold or do: the weight a van carries, the number of jobs a machine
    runs at once.

**Constraint**
:   A rule that a plan must follow. See also *hard* and *soft*.

**CP-SAT**
:   The OR-Tools engine behind every domain except routing. It can prove that a plan is the
    best possible.

**Deadline** (job-shop)
:   A time by which a step **must** end. A plan that misses it is not allowed. Compare *due
    date*.

**Demand** (routing)
:   How much of a vehicle's capacity a stop uses.

**Depot** (routing)
:   The place where vehicles start and end their routes.

**Domain**
:   One kind of problem that orkit can solve, such as job-shop scheduling or vehicle routing.
    There are seven.

**Downtime** (job-shop)
:   A period when a machine cannot work, such as maintenance.

**Due date** (job-shop)
:   A time by which a step **should** end. Being late is allowed but counts against the plan.
    Compare *deadline*.

**Feasible**
:   A plan that follows all the hard rules. Also a status: `FEASIBLE` means "a valid plan,
    not proven to be the best".

**Gap**
:   How far a plan might be from the best possible, as a fraction. A gap of 0.05 means the
    best possible plan is at most 5% better. See [Diagnostics](diagnostics.md#the-gap).

**Goal** (also *objective*)
:   What "best" means for your problem: shortest, cheapest, most valuable.

**Guillotine cut** (cutting)
:   A straight cut that goes all the way across a sheet, from one edge to the other. Many
    saws can only cut this way.

**Hard rule**
:   A rule that can never be broken. If the hard rules clash, there is no plan.

**Haversine distance** (routing)
:   The straight-line distance between two points on the globe, "as the crow flies". Shorter
    than the distance by road.

**Heuristic**
:   A method that finds good answers quickly without proving that they are the best. Routing
    uses heuristics.

**Horizon**
:   The period that a plan covers, for example "seven days starting Monday 6 July".

**Id**
:   The name you give to a thing in your model, such as `"Van-1"`. Ids must be unique within
    their kind.

**Infeasible**
:   No plan can follow all the hard rules at once.

**Kerf** (cutting)
:   The material that the saw blade turns into dust at every cut.

**Makespan** (job-shop)
:   The time at which the last step of the whole schedule ends. A short makespan means
    everything is finished early.

**Metaheuristic** (routing)
:   A method for improving routes that can escape when small changes no longer help.

**Model**
:   Your description of the problem: the things, their details and the rules.

**Objective**
:   The score of a plan for the chosen goal. `objective()` returns it.

**Operation** (job-shop)
:   One step of a job: a machine and a duration.

**Optimal**
:   The best possible plan, with proof that none is better.

**Penalty**
:   A number added to the score when something undesirable happens, such as skipping a stop.
    A larger penalty makes the solver try harder to avoid it.

**Precedence** (job-shop)
:   A rule that one thing must finish before another starts.

**Release time** (job-shop)
:   The earliest time a step may start, for example when the material arrives.

**Resource** (booking)
:   Anything an appointment needs and that can be busy: a person, a room, a machine.

**Roster** (scheduling)
:   The finished plan of who works which shift.

**Setup time** (job-shop)
:   Time needed to prepare a machine between two different kinds of work, such as cleaning
    between two paint colors.

**Soft rule**
:   A rule that may be broken at a price. The solver tries to follow it but will break it
    when the alternative is worse.

**Solver**
:   The engine that searches for the best plan. In orkit you get one with `solver()`.

**Tardiness** (job-shop)
:   How late something is compared with its due date. Zero when it is on time.

**Time window**
:   A period during which something is allowed, such as a shop that accepts deliveries from
    9:00 to 11:00.

**Warm start**
:   Starting a search from a plan you already have, instead of from nothing.

**Weight** (of a goal)
:   A number that says how much a goal counts compared with the others.

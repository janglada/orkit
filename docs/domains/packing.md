# Container packing

**The problem in one sentence:** you have boxes and containers, and you want to know which
box goes into which container and exactly where, using as few containers as possible.

Unlike the [knapsack](knapsack.md), packing works with real shapes. Every box has a length,
a width and a height, and the answer tells you the position of each box. Boxes never overlap
and never stick out of their container.

"Container" can be a shipping container, a truck, a pallet or a cardboard box.

Package `io.github.janglada.orkit.packing` · start with `Optimize.packing()` · result `PackingSolution`

## Quick start

Four pieces of furniture go into a van. Sizes are in centimeters, weights in kilograms.

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.packing.*;
import java.time.Duration;

PackingSolution sol = Optimize.packing()
        .container("Van", c -> c.length(300).width(150).height(150).maxWeight(800))
        .box("Fridge",   b -> b.length(70).width(70).height(140).weight(90))
        .box("Washer",   b -> b.length(60).width(60).height(85).weight(70))
        .box("Sofa",     b -> b.length(200).width(90).height(80).weight(60))
        .box("Cushions", b -> b.length(100).width(80).height(40).weight(5))
        .requireSupport(true)                 // nothing may float in mid-air
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

System.out.println(sol.status() + ", containers used: " + sol.containersUsed());
for (ContainerAssignment c : sol.assignments()) {
    System.out.printf("%s: %d boxes, weight %d of %d%n",
            c.containerId(), c.boxes().size(), c.usedWeight(), c.maxWeight());
    for (ContainerAssignment.BoxPlacement p : c.boxes()) {
        System.out.printf("  %-8s at (%d,%d,%d), size %dx%dx%d%n",
                p.boxId(), p.x(), p.y(), p.z(), p.length(), p.width(), p.height());
    }
}
```

Prints something like:

```text
OPTIMAL, containers used: 1
Van: 4 boxes, weight 225 of 800
  Fridge   at (0,1,0), size 70x70x140
  Washer   at (0,71,0), size 60x60x85
  Sofa     at (70,1,0), size 200x90x80
  Cushions at (70,1,80), size 100x80x40
```

The cushions sit on top of the sofa: their height `z` is 80, exactly the height of the sofa.
Many layouts are equally good, so the exact positions may differ on your machine.

### How to read a position

Imagine standing at one bottom corner of the empty container. That corner is `(0, 0, 0)`.

- `x` runs along the container's **length**.
- `y` runs along its **width**.
- `z` goes **up**.

A box "at (70, 1, 80)" has its nearest bottom corner 70 along the length, 1 along the width
and 80 above the floor. Its size tells you how far it extends from there.

## What you must declare

Every container needs four numbers, and so does every box:

```java
.container("Van", c -> c.length(300).width(150).height(150).maxWeight(800))
.box("Fridge",    b -> b.length(70).width(70).height(140).weight(90))
```

Use the same units everywhere: all sizes in centimeters, for example, and all weights in
kilograms. They must be whole numbers.

The total weight of the boxes in a container never exceeds its `maxWeight`.

## Goals: what "best" means

| Goal | In plain words |
|---|---|
| `Goals.minimizeContainersUsed()` (default) | Pack **everything**, in as few containers as possible. |
| `Goals.maximizeVolumeUtilization()` | Fill the space as well as possible. Boxes **may be left out**. |

### Fewest containers

Three heavy parts and two trucks that carry 1000 kg each:

```java
PackingSolution sol = Optimize.packing()
        .container("Truck-1", c -> c.length(600).width(240).height(240).maxWeight(1000))
        .container("Truck-2", c -> c.length(600).width(240).height(240).maxWeight(1000))
        .box("Engine",  b -> b.length(100).width(80).height(80).weight(700))
        .box("Gearbox", b -> b.length(80).width(60).height(60).weight(400))
        .box("Wheels",  b -> b.length(120).width(120).height(60).weight(200))
        .solver().solve();

for (ContainerAssignment a : sol.assignments()) {
    System.out.printf("%s: %s, %d of %d kg%n", a.containerId(),
            a.boxes().stream().map(ContainerAssignment.BoxPlacement::boxId).toList(),
            a.usedWeight(), a.maxWeight());
}
```

```text
Truck-1: [Engine, Wheels], 900 of 1000 kg
Truck-2: [Gearbox], 400 of 1000 kg
```

There is plenty of room in one truck, but 1300 kg is too heavy for it. So two trucks are
needed.

Under this goal every box must be packed. If the boxes do not all fit in the containers you
declared, the status is `INFEASIBLE`. Add a container, or switch to the next goal.

### Best use of space

One car trunk, three things, and they do not all fit:

```java
PackingSolution sol = Optimize.packing()
        .container("Trunk", c -> c.length(100).width(50).height(50).maxWeight(100))
        .box("Suitcase", b -> b.length(70).width(50).height(50).weight(20))
        .box("Cooler",   b -> b.length(50).width(50).height(50).weight(15))
        .box("Bag",      b -> b.length(30).width(50).height(50).weight(5))
        .goal(Goals.maximizeVolumeUtilization())
        .solver().solve();

System.out.println("Packed:   " + sol.assignments().get(0).boxes().stream()
        .map(ContainerAssignment.BoxPlacement::boxId).toList());
System.out.println("Left out: " + sol.unassignedBoxes());
```

```text
Packed:   [Suitcase, Bag]
Left out: [Cooler]
```

The suitcase and the bag fill the trunk completely (70 + 30 = 100). No other choice uses
more of the space.

### Boxes that fit nowhere

A box that is larger than every container can never be packed. It is listed in
`unassignedBoxes()` under either goal, and the rest is packed as usual.

```java
.container("Small", c -> c.length(50).width(50).height(50).maxWeight(100))
.box("Wardrobe", b -> b.length(200).width(60).height(60).weight(40))   // far too long
.box("Lamp",     b -> b.length(20).width(20).height(40).weight(2))

sol.unassignedBoxes();     // [Wardrobe]
```

## Turning boxes

By default a box keeps the orientation you declared: its length along the container's
length, its height pointing up. With `rotatable(true)` the solver may turn it any way,
including laying it on its side.

A fridge that is too tall to stand in a low crate:

```java
PackingSolution sol = Optimize.packing()
        .container("Crate", c -> c.length(100).width(50).height(50).maxWeight(500))
        .box("Fridge", b -> b.length(40).width(40).height(90).weight(60)   // too tall standing up
                .rotatable(true))
        .solver().solve();

ContainerAssignment.BoxPlacement p = sol.assignments().get(0).boxes().get(0);
System.out.printf("%s placed as %dx%dx%d%n", p.boxId(), p.length(), p.width(), p.height());
```

```text
Fridge placed as 90x40x40
```

The solver laid the fridge down. Without `rotatable(true)` it would not fit, and it would end
up in `unassignedBoxes()`.

The `length()`, `width()` and `height()` of a placement are always the sizes **as placed**,
after any turning. `p.orientation()` gives the same three numbers as one object, and is
`null` for a box that may not be turned.

Do not allow rotation for things that must stay upright.

## Stacking rules

Three settings control what may go on top of what.

| Method | In plain words |
|---|---|
| `maxStackedWeight(w)` | The boxes above this one may weigh at most `w` in total. |
| `above(otherId)` | If both boxes are in the same container, this one is higher up than the other. |
| `below(otherId)` | If both boxes are in the same container, this one is lower down than the other. |

**Example:** a tall, narrow van where the three boxes can only go on top of each other. The
books may carry at most 20 kg, and the eggs must be above the books.

```java
PackingSolution sol = Optimize.packing()
        .container("Van", c -> c.length(100).width(100).height(200).maxWeight(1000))
        .box("Anvil", b -> b.length(100).width(100).height(60).weight(300))
        .box("Books", b -> b.length(100).width(100).height(60).weight(80)
                .maxStackedWeight(20))            // at most 20 kg on top
        .box("Eggs",  b -> b.length(100).width(100).height(60).weight(10)
                .above("Books"))                  // must be higher than the books
        .requireSupport(true)
        .solver().solve();

sol.assignments().get(0).boxes().stream()
        .sorted(Comparator.comparingLong(ContainerAssignment.BoxPlacement::z))
        .forEach(p -> System.out.printf("%s sits at height %d%n", p.boxId(), p.z()));
```

```text
Anvil sits at height 0
Books sits at height 60
Eggs sits at height 120
```

The 300 kg anvil cannot go on the books, so it goes on the floor. Only the 10 kg of eggs end
up above the books.

Things to know:

- `above` and `below` only matter when the two boxes share a container. If they travel in
  different containers, the rule is satisfied.
- "Higher up" means the whole box: its bottom is at or above the top of the other box. It
  does not have to sit directly on it.
- Mistakes are rejected with a `ModelException`: a box above itself, a name that does not
  exist, or two rules that contradict each other (A above B and B above A).

## No floating boxes

```java
.requireSupport(true)
```

Without this setting the solver only makes sure that boxes do not overlap. A box may then
hang in mid-air, which no real load can do.

With it, every box either stands on the floor or sits on top of one other box. Its whole
underside must rest on that box.

!!! note "Stricter than real life"
    A box may not rest across two boxes, and may not stick out over the edge of the box
    below. Some loads that a person could build are therefore not found. In exchange, every
    plan you get can be loaded.

Turn it on whenever people will load the container from your plan. Leave it off when you
only want to know how many containers you need.

## Reading the result

```java
sol.containersUsed();           // 1: containers that hold at least one box
sol.assignments();              // one entry per container, empty containers included
sol.assignmentOf("Van");        // Optional with that container's entry
sol.containerOf("Sofa");        // Optional[Van]
sol.unassignedBoxes();          // names of boxes that were not packed
```

For each container (`ContainerAssignment`):

```java
c.containerId();     // "Van"
c.boxes();           // one BoxPlacement per box
c.usedWeight();      // 225
c.maxWeight();       // 800
c.usedVolume();      // 2752000: total volume of the boxes inside
```

For each box (`BoxPlacement`): `boxId()`, the position `x()`, `y()`, `z()`, and the size as
placed `length()`, `width()`, `height()`.

## How large a load?

A few dozen boxes are usually packed, and proven best, within seconds. A test with 60 boxes
that may be turned finishes in about 10 seconds on a laptop.

Larger loads still work. Expect a `FEASIBLE` plan with a
[gap](../guide/diagnostics.md#how-good-is-my-plan) instead of `OPTIMAL`, and give the solver
more time.

## Reference

### Describing the problem

| Method | Meaning |
|---|---|
| `container(id, c -> c.length(l).width(w).height(h).maxWeight(m))` | A container. All four values are required. |
| `box(id, b -> b.length(l).width(w).height(h).weight(g))` | A box. All four values are required. |
| `requireSupport(boolean)` | No floating boxes. Off by default. |
| `goal(PackingGoal)` | Choose the goal. |

### `BoxConfig` options

| Method | Meaning |
|---|---|
| `rotatable(true)` | The box may be turned any way. |
| `maxStackedWeight(w)` | Most weight allowed above this box. |
| `above(id)`, `below(id)` | Vertical order with another box in the same container. |

### `PackingSolution`

| Method | Returns |
|---|---|
| `assignments()` | One `ContainerAssignment` per container, empty ones included. |
| `assignmentOf(container)` | That container's entry. Empty if the name is unknown. |
| `containerOf(box)` | The container holding the box. Empty if it was not packed or the name is unknown. |
| `unassignedBoxes()` | Names of boxes that were not packed. |
| `containersUsed()` | Number of containers holding at least one box. |

### `ContainerAssignment`

| Method | Returns |
|---|---|
| `containerId()` | The container's name. |
| `length()`, `width()`, `height()`, `maxWeight()` | The container's own values. |
| `boxes()` | One `BoxPlacement` per box inside. |
| `usedVolume()`, `usedWeight()` | Totals of the boxes inside. |

### `BoxPlacement`

| Method | Returns |
|---|---|
| `boxId()` | The box's name. |
| `x()`, `y()`, `z()` | Position of the box's corner nearest to the container's origin. |
| `length()`, `width()`, `height()` | Size as placed, after any turning. |
| `orientation()` | The chosen orientation, or `null` if the box may not be turned. |

The result also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

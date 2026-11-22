# Container packing

Place boxes into containers in three dimensions without overlap, within each container's
weight limit, optionally with rotation and realistic stacking.

Package `io.github.janglada.orkit.packing` · entry point `Optimize.packing()` · result `PackingSolution`

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.packing.*;
import java.time.Duration;

PackingSolution sol = Optimize.packing()
        .container("40ft", c -> c.length(1200).width(235).height(269).maxWeight(28000))
        .container("20ft", c -> c.length(586).width(235).height(269).maxWeight(22000))
        .box("Pallet-A", b -> b.length(120).width(80).height(150).weight(800))
        .box("Pallet-B", b -> b.length(120).width(80).height(150).weight(1200)
                .rotatable(true)
                .maxStackedWeight(900))
        .box("Machine-X", b -> b.length(200).width(100).height(180).weight(4500)
                .below("Pallet-B"))
        .requireSupport(true)
        .goal(Goals.minimizeContainersUsed())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

for (ContainerAssignment a : sol.assignments()) {
    System.out.printf("%s: %d boxes, weight %d/%d%n",
            a.containerId(), a.boxes().size(), a.usedWeight(), a.maxWeight());
    for (ContainerAssignment.BoxPlacement p : a.boxes()) {
        System.out.printf("  %s at (%d,%d,%d) size %dx%dx%d%n",
                p.boxId(), p.x(), p.y(), p.z(), p.length(), p.width(), p.height());
    }
}
```

Coordinates are the box's origin corner inside its container: `x` along the length, `y` along
the width, `z` up.

## Goals

| Goal | Behavior |
|---|---|
| `Goals.minimizeContainersUsed()` (default) | Every box that fits in some container must be packed; use as few containers as possible. |
| `Goals.maximizeVolumeUtilization()` | Pack as much box volume as possible; boxes may be left out. |

Boxes too large for every container are reported in `unassignedBoxes()` under either goal.

## Box options

All are off by default, which gives the classical no-overlap model.

| Method | Meaning |
|---|---|
| `rotatable(true)` | The box may take any distinct axis permutation of its dimensions (up to six). |
| `above(id)`, `below(id)` | Vertical order relative to another box when both share a container. |
| `maxStackedWeight(w)` | Caps the total weight of boxes placed above this one. |

The chosen `Orientation` is reported on each `BoxPlacement` (`null` for a box that is not
rotatable), and the placement's `length`/`width`/`height` are the placed extents.

Declaring a box above or below itself, an unknown id, or two contradictory orders raises
`ModelException`.

## Physical support

```java
.requireSupport(true)
```

Without it, the model only forbids overlap, so a box may hover in mid-air. With it, every
packed box either stands on the container floor or rests directly on one other box: its bottom
touches that box's top and its footprint lies inside that box's footprint.

!!! note
    A box may not bridge two supporters, and overhang is not allowed. This is conservative:
    some loadable arrangements are excluded.

## Reference

### Configuration

| Method | Meaning |
|---|---|
| `container(id, c -> c.length(l).width(w).height(h).maxWeight(m))` | A container; all four are required. |
| `box(id, b -> b.length(l).width(w).height(h).weight(g))` | A box; all four are required. |
| `requireSupport(boolean)` | Physical support. |
| `goal(PackingGoal)` | The objective. |

### `PackingSolution`

| Method | Returns |
|---|---|
| `assignments()` | One `ContainerAssignment` per container, empty ones included. |
| `assignmentOf(container)` | That container's assignment, if the id is known. |
| `containerOf(box)` | The container holding the box; empty if unassigned or unknown. |
| `unassignedBoxes()` | Boxes not packed. |
| `containersUsed()` | Containers holding at least one box. |

`ContainerAssignment` has the container's dimensions, `boxes()`, `usedVolume()` and
`usedWeight()`. `BoxPlacement` has `boxId()`, `x()`, `y()`, `z()`, `length()`, `width()`,
`height()` and `orientation()`.

## Performance

Each box has one position and each pair of boxes one set of separation choices, strengthened
by volume and cross-section relaxations and by symmetry breaking between identical boxes and
identical containers. A few dozen rotatable boxes typically solve to proven optimality in
seconds; larger loads return good `FEASIBLE` plans with a gap.

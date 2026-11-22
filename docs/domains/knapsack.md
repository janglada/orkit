# Multiple knapsack

Choose which items go into which bins so that no bin exceeds its capacity and the total profit
of the packed items is as large as possible. Items that do not fit are left out.

Package `io.github.janglada.orkit.knapsack` · entry point `Optimize.multipleKnapsack()` · result `MultipleKnapsackSolution`

## Quick start

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.knapsack.BinAssignment;
import io.github.janglada.orkit.knapsack.MultipleKnapsackSolution;
import java.time.Duration;

MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
        .bin("T1", b -> b.capacity(10))
        .bin("T2", b -> b.capacity(10))
        .item("I1", i -> i.profit(7).weight(3))
        .item("I2", i -> i.profit(5).weight(6))
        .item("I3", i -> i.profit(9).weight(5))
        .item("I4", i -> i.profit(4).weight(2))
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .solve();

System.out.println(sol.status() + " totalProfit=" + sol.totalProfit());
for (BinAssignment a : sol.assignments()) {
    System.out.printf("  %s: %s (load %d/%d, profit %d)%n",
            a.bin(), a.items(), a.usedCapacity(), a.capacity(), a.profit());
}
sol.binOf("I2").ifPresent(bin -> System.out.println("I2 is in " + bin));
```

## Model

- Each item goes into at most one bin.
- The weights in a bin sum to at most its capacity.
- The goal is `Goals.maximizeProfit()` (the only goal, and the default).

The model is always feasible: in the worst case nothing is packed.

## Reference

### Configuration

| Method | Meaning |
|---|---|
| `bin(id, b -> b.capacity(c))` | A bin with a weight capacity. |
| `item(id, i -> i.profit(p).weight(w))` | An item with a profit and a weight. |
| `goal(KnapsackGoal)` | The objective. |

### `MultipleKnapsackSolution`

| Method | Returns |
|---|---|
| `totalProfit()` | Profit of all packed items. |
| `assignments()` | One `BinAssignment` per bin, in declaration order, empty bins included. |
| `assignmentOf(bin)` | That bin's assignment, if the id is known. |
| `binOf(item)` | The bin holding the item; empty if unassigned or unknown. |
| `unassignedItems()` | Items left out. |

`BinAssignment` has `bin()`, `capacity()`, `items()`, `usedCapacity()` and `profit()`.

# Multiple knapsack

**The problem in one sentence:** you have more items than fit in your bins, so choose the
items, and the bin for each, that give the highest total value.

Each item has a *weight* (how much room it takes) and a *profit* (how much it is worth to
you). Each bin has a *capacity* (how much weight it can hold). Items that do not fit are left
out.

"Bin" and "weight" are just names. The bins can be trucks, budgets or servers, and the weight
can be kilograms, euros or gigabytes.

Package `io.github.janglada.orkit.knapsack` · start with `Optimize.multipleKnapsack()` · result `MultipleKnapsackSolution`

## Quick start

Four items, two bins of capacity 10.

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
```

Prints:

```text
OPTIMAL totalProfit=25
  T1: [I1, I2] (load 9/10, profit 12)
  T2: [I3, I4] (load 7/10, profit 13)
```

Everything fits here, so all four items are packed.

## The rules

- An item goes into **one** bin or is left out. It is never split.
- The weights in a bin add up to at most its capacity.
- The goal is the highest total profit. It is the only goal, so you never need to call
  `goal(...)`.

There is always an answer: in the worst case nothing is packed and the profit is 0. A
knapsack model is never `INFEASIBLE`.

## When not everything fits

One backpack that holds 10 kg, and four things you would like to take:

```java
MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
        .bin("Backpack", b -> b.capacity(10))
        .item("Laptop", i -> i.profit(10).weight(4))
        .item("Camera", i -> i.profit(8).weight(3))
        .item("Books",  i -> i.profit(5).weight(6))
        .item("Tent",   i -> i.profit(9).weight(12))   // heavier than the backpack can hold
        .solver().solve();

System.out.println("Packed:   " + sol.assignmentOf("Backpack").orElseThrow().items());
System.out.println("Left out: " + sol.unassignedItems());
System.out.println("Where is the tent? " + sol.binOf("Tent"));
```

Prints:

```text
Packed:   [Laptop, Camera]
Left out: [Books, Tent]
Where is the tent? Optional.empty
```

The laptop and camera together weigh 7 kg and are worth 18. Adding the books would make
13 kg, which is too much. The tent can never go: it weighs more than the backpack holds.

## A larger example: loading two vans

Value in euros, weight in kilograms.

```java
MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
        .bin("Van-1", b -> b.capacity(800))
        .bin("Van-2", b -> b.capacity(500))
        .item("Fridge",   i -> i.profit(600).weight(450))
        .item("Sofa",     i -> i.profit(400).weight(380))
        .item("Piano",    i -> i.profit(900).weight(700))
        .item("Bookcase", i -> i.profit(150).weight(120))
        .item("Mattress", i -> i.profit(200).weight(90))
        .solver().solve();

for (BinAssignment van : sol.assignments()) {
    System.out.printf("%s carries %s: %d of %d kg, worth %d%n",
            van.bin(), van.items(), van.usedCapacity(), van.capacity(), van.profit());
}
System.out.println("Stays behind: " + sol.unassignedItems());
System.out.println("Total value:  " + sol.totalProfit());
```

Prints:

```text
Van-1 carries [Piano, Mattress]: 790 of 800 kg, worth 1100
Van-2 carries [Fridge]: 450 of 500 kg, worth 600
Stays behind: [Sofa, Bookcase]
Total value:  1700
```

## Reading the result

```java
sol.totalProfit();                    // 1700: value of everything that was packed
sol.assignments();                    // one entry per bin, in the order you declared them
sol.assignmentOf("Van-1");            // Optional with that van's entry
sol.binOf("Piano");                   // Optional[Van-1]
sol.binOf("Sofa");                    // Optional.empty: it stays behind
sol.unassignedItems();                // [Sofa, Bookcase]
```

A bin that received nothing still appears in `assignments()`, with an empty list of items.

## Tips

- **Weights and profits are whole numbers.** Use grams instead of 2.5 kg, cents instead of
  9.99. See [Use whole numbers](../guide/modeling-notes.md#use-whole-numbers).
- **Profits, weights and capacities must be at least 1.** A profit of 0 is rejected:
  `profit() must be >= 1, got: 0`. An item worth nothing would never be packed anyway.
- **Do you need to know where each box physically goes?** Then you want
  [container packing](packing.md), which works with real dimensions.

## Reference

### Describing the problem

| Method | Meaning |
|---|---|
| `bin(id, b -> b.capacity(c))` | A bin that holds up to `c` units of weight. |
| `item(id, i -> i.profit(p).weight(w))` | An item worth `p` that takes `w` units of weight. |
| `goal(Goals.maximizeProfit())` | The goal. It is the default and the only choice. |

### `MultipleKnapsackSolution`

| Method | Returns |
|---|---|
| `totalProfit()` | Total profit of all packed items. |
| `assignments()` | One `BinAssignment` per bin, in declaration order, empty bins included. |
| `assignmentOf(bin)` | That bin's `BinAssignment`. Empty if no bin has that name. |
| `binOf(item)` | The name of the bin holding the item. Empty if the item was left out or the name is unknown. |
| `unassignedItems()` | Names of the items that were left out. |

### `BinAssignment`

| Method | Returns |
|---|---|
| `bin()` | The bin's name. |
| `capacity()` | The bin's capacity. |
| `items()` | Names of the items in this bin. |
| `usedCapacity()` | Total weight of those items. |
| `profit()` | Total profit of those items. |

The result also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

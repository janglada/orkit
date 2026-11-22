# Cutting stock

**The problem in one sentence:** you need a number of pieces, you have rolls, bars or sheets
to cut them from, and you want to use as little material as possible.

A few words used on this page:

- **Stock** is the material you start with: a roll of paper, a steel bar, a wooden plank, a
  sheet of glass.
- A **piece** is something you need to cut out of the stock.
- **Waste** is the material left over on the stock you used.

There are two kinds of cutting problem:

- **Rolls and bars (1D).** Only the length matters. You cut a long thing into shorter things.
- **Sheets (2D).** Length and width both matter. You cut rectangles out of a larger
  rectangle.

Package `io.github.janglada.orkit.cutting` · start with `Optimize.cuttingStock()` · result `CuttingSolution`

## Quick start

=== "Rolls and bars (1D)"

    You have three planks of 240 cm. You need four shelves of 80 cm and two sides of 110 cm.

    ```java
    import io.github.janglada.orkit.Optimize;
    import io.github.janglada.orkit.cutting.*;

    CuttingSolution sol = Optimize.cuttingStock()
            .stock("Plank", s -> s.length(240).quantity(3))          // three planks, 240 cm each
            .required("Shelf", p -> p.length(80).quantity(4))        // four shelves of 80 cm
            .required("Side",  p -> p.length(110).quantity(2))       // two sides of 110 cm
            .solver().solve();

    for (StockAssignment plank : sol.assignments()) {
        System.out.print(plank.stockId() + ":");
        for (StockAssignment.CutPiece piece : plank.pieces()) {
            System.out.printf("  %s at %d", piece.pieceId(), piece.start());
        }
        System.out.println("  -> " + plank.waste() + " cm left over");
    }
    System.out.println("Planks used: " + sol.totalStockUsed());
    ```

    Prints:

    ```text
    Plank#1:  Shelf#1 at 0  Shelf#2 at 80  -> 80 cm left over
    Plank#2:  Shelf#3 at 0  Side#1 at 80  -> 50 cm left over
    Plank#3:  Shelf#4 at 0  Side#2 at 80  -> 50 cm left over
    Planks used: 3
    ```

    Read `Side#1 at 80` as "the first side piece starts 80 cm from the end of the plank".

=== "Sheets (2D)"

    You have two sheets of 200 × 150. You need four blanks of 60 × 40 and three of 50 × 30.

    ```java
    import io.github.janglada.orkit.Optimize;
    import io.github.janglada.orkit.cutting.*;
    import java.time.Duration;

    CuttingSolution sol = Optimize.cuttingStock()
            .stock("sheet", s -> s.length(200).width(150).cost(120).quantity(2))
            .required("blank-A", p -> p.length(60).width(40).quantity(4).rotatable(true))
            .required("blank-B", p -> p.length(50).width(30).quantity(3))
            .solver().timeLimit(Duration.ofSeconds(15)).solve();

    for (StockAssignment a : sol.assignments()) {
        for (StockAssignment.CutPiece c : a.pieces()) {
            System.out.printf("%s: %s %dx%d at (%d,%d)%s%n", a.stockId(), c.pieceId(),
                    c.length(), c.width(), c.x(), c.y(), c.rotated() ? " rotated" : "");
        }
    }
    ```

    Prints something like:

    ```text
    sheet#1: blank-A#1 60x40 at (0,0)
    sheet#1: blank-A#2 60x40 at (0,40)
    sheet#1: blank-A#3 60x40 at (0,80)
    sheet#1: blank-A#4 60x40 at (60,0)
    sheet#1: blank-B#1 50x30 at (0,120)
    sheet#1: blank-B#2 50x30 at (50,120)
    sheet#1: blank-B#3 50x30 at (100,91)
    ```

    Everything fits on one sheet, so the second one stays untouched. A position `(x, y)` is
    the corner of the piece nearest to the corner of the sheet: `x` along the length, `y`
    along the width.

## Names with a number: `Plank#1`, `Shelf#3`

When you declare `quantity(3)`, you get three separate things. The result tells them apart by
adding `#1`, `#2`, `#3` to the name you chose.

| You declare | The result calls them |
|---|---|
| `stock("Plank", s -> s.length(240).quantity(3))` | `Plank#1`, `Plank#2`, `Plank#3` |
| `required("Shelf", p -> p.length(80).quantity(4))` | `Shelf#1`, `Shelf#2`, `Shelf#3`, `Shelf#4` |

To get the name you chose back, use `stockType()` and `pieceType()`:

```java
piece.pieceId();     // "Shelf#3"
piece.pieceType();   // "Shelf"
plank.stockId();     // "Plank#2"
plank.stockType();   // "Plank"
```

## Rolls or sheets?

You do not choose a mode. It follows from what you declare:

- If **nothing** has a `width`, the model is about rolls and bars.
- If **anything** has a `width`, the model is about sheets, and then every stock and every
  piece must have one.

Mixing the two (a sheet with a width and a piece without) is rejected when you solve:
`2D cutting requires every stock and piece to declare a width (piece 'P' has no width).`

## Describing stock and pieces

| Method | Default | Meaning |
|---|---|---|
| `StockConfig.length(l)` | required | Usable length of one roll, bar or sheet. |
| `StockConfig.width(w)` | none | Usable width of one sheet. |
| `StockConfig.quantity(q)` | 1 | How many you have. |
| `StockConfig.cost(c)` | 1 | Price of one. Used by the goal `minimizeTotalCost()`. |
| `PieceConfig.length(l)` | required | Length of the piece. |
| `PieceConfig.width(w)` | none | Width of the piece, for sheets. |
| `PieceConfig.quantity(q)` | 1 | How many you need. |
| `PieceConfig.rotatable(true)` | `false` | The piece may be turned a quarter turn, for sheets. |

You may declare several kinds of stock, with different sizes and prices. The solver chooses
which ones to use.

Use one unit for all lengths and widths, in whole numbers. Millimeters work well.

## Goals: what "best" means

| Goal | In plain words |
|---|---|
| `Goals.minimizeWaste()` (default) | Use up as little material as possible, counted as total length (rolls) or total area (sheets) of the stock you cut into. |
| `Goals.minimizeStockUsed()` | Cut into as few rolls or sheets as possible. |
| `Goals.minimizeTotalCost()` | Spend as little as possible, using each stock's `cost`. |

When all your stock is the same, the three goals give the same answer. They differ when you
have several sizes or prices.

**Example.** You need four pipes of 290. You have short bars (300 long, price 40) and long
bars (700 long, price 60).

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("Short", s -> s.length(300).cost(40).quantity(4))
        .stock("Long",  s -> s.length(700).cost(60).quantity(2))
        .required("Pipe", p -> p.length(290).quantity(4))
        .goal(goal)
        .solver().solve();
```

| `goal` | Bars used | Material used | Price |
|---|---|---|---|
| `Goals.minimizeWaste()` | 4 short | 1200 | 160 |
| `Goals.minimizeStockUsed()` | 2 long | 1400 | 120 |
| `Goals.minimizeTotalCost()` | 2 long | 1400 | 120 |

Four short bars waste almost nothing (10 on each). Two long bars waste more material (120 on
each) but are cheaper, and mean fewer bars to handle. Which answer is "best" depends on what
you care about.

## When the pieces do not all fit

By default **every piece must be cut**. If your stock is not enough, the status is
`INFEASIBLE`.

```java
CuttingSolution strict = Optimize.cuttingStock()
        .stock("Roll", s -> s.length(100))
        .required("Strip", p -> p.length(30).quantity(4))     // 120 needed, 100 available
        .solver().solve();

strict.status();     // INFEASIBLE
```

To get the best partial answer instead, allow pieces to be left out. Each piece left out
costs a *penalty*, so the solver leaves out as few as it can.

```java
CuttingSolution relaxed = Optimize.cuttingStock()
        .stock("Roll", s -> s.length(100))
        .required("Strip", p -> p.length(30).quantity(4))
        .allowDroppingPieces(1_000)                           // leaving a strip out costs 1,000
        .solver().solve();

System.out.println("Cut:      " + relaxed.assignments().get(0).pieces().stream()
        .map(StockAssignment.CutPiece::pieceId).toList());
System.out.println("Left out: " + relaxed.unplacedPieces());
```

```text
Cut:      [Strip#1, Strip#2, Strip#3]
Left out: [Strip#4]
```

Choose a penalty that is clearly larger than the size or price of your stock. With a small
penalty, the solver may find it "cheaper" to leave a piece out than to cut into a new roll.

## Blade width (kerf)

A saw blade is not infinitely thin. Every cut turns a little material into dust. The width of
that loss is called the *kerf*.

```java
.kerf(3)     // each cut removes 3 units of material
```

**Example.** A bar of 100 and two pieces of 50. On paper they fit exactly. With a real blade
they do not:

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("Bar", s -> s.length(100).quantity(2))
        .required("Half", p -> p.length(50).quantity(2))
        .kerf(kerf)
        .solver().solve();

System.out.println("bars used = " + sol.totalStockUsed());
```

| `kerf` | Bars used | Why |
|---|---|---|
| 0 | 1 | 50 + 50 = 100: an exact fit. |
| 2 | 2 | 50 + 2 + 50 = 102: too long for one bar. |

How it works: two pieces cut from the same stock are kept at least one kerf apart. A piece
may touch the edge of the stock, since no cut is needed there. On sheets the gap applies in
both directions. The material lost to the blade is counted as waste.

## Turning pieces (sheets only)

With `rotatable(true)`, a piece may be turned a quarter turn, so that its length runs along
the width of the sheet.

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("sheet", s -> s.length(100).width(50))
        .required("Tall", p -> p.length(30).width(80).rotatable(true))   // only fits turned
        .solver().solve();

StockAssignment.CutPiece c = sol.assignments().get(0).pieces().get(0);
System.out.printf("%s %dx%d%s%n", c.pieceId(), c.length(), c.width(), c.rotated() ? " rotated" : "");
```

```text
Tall#1 80x30 rotated
```

`rotated()` tells you whether the piece was turned. `length()` and `width()` are the sizes
**as placed**, after turning.

Leave rotation off when direction matters: wood grain, a printed pattern, rolled steel.

Rotation makes no sense for rolls and bars, and is rejected there:
`Piece 'P' is rotatable, but rotation needs a 2D model (declare widths).` It also cannot be
combined with `guillotine(true)`.

## Guillotine cuts (sheets only)

Many machines, such as panel saws and glass cutters, can only cut in a straight line **all
the way across**, from one edge to the opposite edge. This is called a *guillotine cut*. A
layout where a piece sits in the middle, surrounded on all sides, cannot be cut that way.

```java
.guillotine(true)
```

With this setting, every layout is built so that it can be cut edge to edge:

1. First the sheet is cut across into **strips**.
2. Then each strip is cut into **pieces**.
3. A piece that is narrower than its strip gets one extra trim cut.

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("Board", s -> s.length(100).width(60).quantity(2))
        .required("Door",  p -> p.length(60).width(40).quantity(2))
        .required("Panel", p -> p.length(40).width(20).quantity(3))
        .guillotine(true)
        .solver().timeLimit(Duration.ofSeconds(10)).solve();

for (StockAssignment b : sol.assignments()) {
    System.out.println(b.stockId() + ": " + b.pieces().stream()
            .map(c -> String.format("%s %dx%d at (%d,%d)",
                    c.pieceId(), c.length(), c.width(), c.x(), c.y()))
            .collect(Collectors.joining(", ")));
}
```

Prints something like:

```text
Board#1: Door#2 60x40 at (0,0), Panel#1 40x20 at (0,40)
Board#2: Door#1 60x40 at (0,0), Panel#2 40x20 at (60,0), Panel#3 40x20 at (0,40)
```

On board 2, the first cut goes across at `y = 40`. The lower strip holds the door and a
panel side by side; the upper strip holds the other panel.

!!! note "Simple layouts only"
    Only this strips-then-pieces pattern is tried. Cleverer edge-to-edge layouts with more
    levels of cuts exist, and they are not explored. The result can therefore use a little
    more material than the best possible guillotine layout. It is always correct and always
    cuttable.

For rolls and bars the setting has no effect: every cut already goes all the way across.

## Reading the result

```java
sol.assignments();        // one entry per roll or sheet, unused ones included
sol.totalStockUsed();     // how many rolls or sheets were cut into
sol.totalWaste();         // leftover length (rolls) or area (sheets) on the stock that was used
sol.unplacedPieces();     // pieces that were left out, if you allowed that
```

For each roll or sheet (`StockAssignment`):

```java
a.stockId();      // "Plank#2"
a.stockType();    // "Plank"
a.length();       // 240
a.pieces();       // the pieces cut from it; empty if it was not used
a.waste();        // 50: what is left over
```

For each piece (`CutPiece`):

```java
c.pieceId();      // "Side#1"
c.pieceType();    // "Side"
c.start();        // 80: where the piece begins along the length (same as x())
c.x(); c.y();     // position on a sheet
c.length();       // 110
c.rotated();      // whether it was turned
```

Stock that was not needed still appears in `assignments()`, with no pieces and no waste. To
list only what you actually cut into:

```java
sol.assignments().stream()
        .filter(a -> !a.pieces().isEmpty())
        .forEach(a -> System.out.println(a.stockId()));
```

## A fuller example

Steel bars in two lengths, three kinds of piece, and a 3 mm blade:

```java
CuttingSolution sol = Optimize.cuttingStock()
        .stock("R1", s -> s.length(6000).cost(100).quantity(5))
        .stock("R2", s -> s.length(8000).cost(130).quantity(3))
        .required("Bar-A", p -> p.length(1500).quantity(8))
        .required("Bar-B", p -> p.length(1200).quantity(6))
        .required("Bar-C", p -> p.length(900).quantity(4))
        .kerf(3)
        .goal(Goals.minimizeWaste())
        .solver()
        .timeLimit(Duration.ofSeconds(15))
        .solve();

for (StockAssignment a : sol.assignments()) {
    System.out.printf("%s (len=%d): %d pieces, waste=%d%n",
            a.stockId(), a.length(), a.pieces().size(), a.waste());
}
```

```text
R1#1 (len=6000): 5 pieces, waste=300
R1#2 (len=6000): 5 pieces, waste=300
R1#3 (len=6000): 4 pieces, waste=300
R1#4 (len=6000): 4 pieces, waste=300
R1#5 (len=6000): 0 pieces, waste=0
R2#1 (len=8000): 0 pieces, waste=0
R2#2 (len=8000): 0 pieces, waste=0
R2#3 (len=8000): 0 pieces, waste=0
```

Eighteen pieces come out of four of the 6-meter bars. The fifth bar and all the 8-meter bars
stay in the rack.

## How large a problem?

- **Rolls and bars** scale very well. What matters is the number of **different** piece
  lengths, not the number of pieces. A test with 300 pieces of 4 lengths on 90 rolls is
  solved, and proven best, in about 10 seconds.
- **Sheets** are harder, because every single piece needs a position. A few dozen pieces are
  comfortable. Beyond that, expect `FEASIBLE` plans and give the solver more time.

## Reference

### `CuttingBuilder`

| Method | Purpose |
|---|---|
| `stock(id, config)` | Declare a kind of stock. |
| `required(id, config)` | Declare a kind of piece you need. |
| `goal(CuttingGoal)` | Choose the goal. |
| `allowDroppingPieces(penalty)` | Allow pieces to be left out, at this price each. |
| `kerf(k)` | Width of material lost at each cut. |
| `guillotine(boolean)` | Only edge-to-edge cuts, for sheets. |
| `build()`, `solver()` | Freeze the problem, or go straight to solving. |

### `CuttingSolution`

| Method | Returns |
|---|---|
| `assignments()` | One `StockAssignment` per roll or sheet, unused ones included. |
| `unplacedPieces()` | Names of the pieces that were left out. |
| `totalStockUsed()` | Number of rolls or sheets holding at least one piece. |
| `totalWaste()` | Leftover length (rolls) or area (sheets) on the stock that was used. |
| `totalAreaWaste()` | Leftover area, for sheets. 0 for rolls. |

### `StockAssignment`

| Method | Returns |
|---|---|
| `stockId()`, `stockType()` | The numbered name, and the name you declared. |
| `length()`, `width()`, `cost()` | The stock's own values. |
| `pieces()` | The pieces cut from it. |
| `waste()` | What is left over on it. |

### `CutPiece`

| Method | Returns |
|---|---|
| `pieceId()`, `pieceType()` | The numbered name, and the name you declared. |
| `length()`, `width()` | Size as placed. |
| `x()`, `y()` | Position on the stock. `y` is 0 for rolls. |
| `start()` | The same as `x()`. |
| `rotated()` | Whether the piece was turned. |

The result also has everything described in
[Solutions](../guide/concepts.md#solutions-reading-the-answer): `status()`, `objective()`,
`runtime()` and so on.

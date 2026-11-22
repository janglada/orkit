# Cutting stock

Cut a set of required pieces out of stock rolls (1D) or sheets (2D), using as little stock as
possible.

Package `io.github.janglada.orkit.cutting` · entry point `Optimize.cuttingStock()` · result `CuttingSolution`

## Quick start

=== "1D rolls"

    ```java
    import io.github.janglada.orkit.Optimize;
    import io.github.janglada.orkit.cutting.*;
    import java.time.Duration;

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

=== "2D sheets"

    ```java
    CuttingSolution sol = Optimize.cuttingStock()
            .stock("sheet", s -> s.length(200).width(150).cost(120).quantity(2))
            .required("blank-A", p -> p.length(60).width(40).quantity(4).rotatable(true))
            .required("blank-B", p -> p.length(50).width(30).quantity(3))
            .goal(Goals.minimizeWaste())
            .solver().timeLimit(Duration.ofSeconds(15)).solve();

    for (StockAssignment a : sol.assignments()) {
        for (StockAssignment.CutPiece c : a.pieces()) {
            System.out.printf("%s: %s %dx%d at (%d,%d)%s%n", a.stockId(), c.pieceId(),
                    c.length(), c.width(), c.x(), c.y(), c.rotated() ? " rotated" : "");
        }
    }
    ```

## 1D or 2D

A model is 2D as soon as any stock or piece declares a `width`; then every stock and piece
must declare one. Mixed models are rejected.

## Stock and pieces

| Method | Default | Meaning |
|---|---|---|
| `StockConfig.length(l)` | required | Usable length. |
| `StockConfig.width(w)` | 0 (1D) | Usable width of a sheet. |
| `StockConfig.cost(c)` | 1 | Cost of consuming one unit of this stock. |
| `StockConfig.quantity(q)` | 1 | Units available. |
| `PieceConfig.length(l)` | required | Piece length. |
| `PieceConfig.width(w)` | 0 (1D) | Piece width. |
| `PieceConfig.quantity(q)` | 1 | Pieces demanded. |
| `PieceConfig.rotatable(true)` | false | The piece may turn 90 degrees (2D only). |

`quantity` expands a type into instances named `"<id>#<n>"`, for example `R1#2` or `Bar-A#5`.
`StockAssignment.stockType()` and `CutPiece.pieceType()` return the declared id.

## Goals

| Goal | Minimizes |
|---|---|
| `Goals.minimizeWaste()` (default) | Total stock consumed: length in 1D, area in 2D. |
| `Goals.minimizeStockUsed()` | Number of rolls or sheets used. |
| `Goals.minimizeTotalCost()` | Sum of `cost` over used stock. |

The first two differ when stock comes in different sizes.

## Optional pieces

All pieces are required by default, so a model whose pieces cannot fit is `INFEASIBLE`.
`allowDroppingPieces(penalty)` lets the solver leave pieces out at that cost each; they are
listed in `unplacedPieces()`.

## Kerf

```java
.kerf(3)     // the blade removes 3 units at every cut
```

Pieces on the same roll or sheet are kept at least `kerf` apart (along both axes in 2D); a
piece may touch the stock edge. The lost material counts as waste.

## Rotation (2D)

A `rotatable(true)` piece may be placed with its length along the sheet's width.
`CutPiece.rotated()` tells you, and `length()`/`width()` are then the placed extents. Rotation
is rejected in 1D models and together with `guillotine(true)`.

## Guillotine cuts (2D)

```java
.guillotine(true)
```

Many saws can only cut straight across a sheet, edge to edge. With `guillotine(true)` the
layout is built from *shelves*: the sheet is cut across its width into strips, each as wide as
its widest piece, and each strip is cut along its length into pieces, with a trim cut where a
piece is narrower than its strip. Every such layout can be produced by edge-to-edge cuts.

!!! note "Two-stage layouts only"
    Guillotine layouts needing more stages are not explored, so the result may use more stock
    than the best possible free-form guillotine layout. The option is ignored in 1D, where
    every layout is guillotine.

## Reference

### `CuttingBuilder`

`stock(id, config)`, `required(id, config)`, `goal(goal)`, `allowDroppingPieces(penalty)`,
`kerf(k)`, `guillotine(boolean)`, `build()`, `solver()`.

### `CuttingSolution`

| Method | Returns |
|---|---|
| `assignments()` | One `StockAssignment` per roll or sheet, empty ones included. |
| `unplacedPieces()` | Piece instances left out. |
| `totalStockUsed()` | Rolls or sheets holding at least one piece. |
| `totalWaste()` | Unused length (1D) or area (2D) on used stock. |
| `totalAreaWaste()` | The same for 2D sheets only. |

`StockAssignment` has `stockId()`, `stockType()`, `length()`, `width()`, `cost()`, `pieces()`
and `waste()`. `CutPiece` has `pieceId()`, `pieceType()`, `length()`, `width()`, `x()`, `y()`,
`rotated()` and `start()` (same as `x()`).

## How it is modeled

- **1D** counts how many pieces of each type go on each roll, so the model grows with the
  number of piece *types*, not pieces. Offsets and instance ids are assigned after the solve,
  in roll order, and dropped instances are the highest-numbered ones.
- **2D** places one rectangle per piece instance on a sheet, with a no-overlap constraint per
  sheet and symmetry breaking between identical sheets and identical pieces.

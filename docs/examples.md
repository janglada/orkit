# Examples

Seven complete programs, one per domain. Each one is larger than the snippets on the domain
pages and prints a readable report, so they are a good place to see how the pieces fit
together.

!!! tip "New here?"
    Start with the small examples instead: [Getting started](guide/getting-started.md) has a
    first program explained line by line, and every [domain page](domains/index.md) opens
    with a quick start.

## How to run them

The examples live in the test sources of the project
(`src/test/java/io/github/janglada/orkit/examples`), so they are not part of the library jar.
From the project folder, run one with Maven:

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.PcbManufacturingJobShop
```

Replace the last word with the name of the example you want.

## The seven examples

| Example | Domain | The story |
|---|---|---|
| [`PcbManufacturingJobShop`](#pcbmanufacturingjobshop) | [Job-shop](domains/jobshop.md) | A factory makes eight kinds of circuit board on five machines. Some steps can run on either of two machines. The program finds the fastest schedule and prints a timetable per machine. |
| [`HospitalNurseScheduling`](#hospitalnursescheduling) | [Shift scheduling](domains/scheduling.md) | Twelve nurses cover 21 shifts in one week, with skills, rest rules and preferences, at the lowest labor cost. |
| [`BarcelonaDeliveryVrp`](#barcelonadeliveryvrp) | [Vehicle routing](domains/routing.md) | Four vans deliver to 18 addresses with opening hours, limited capacity, a distance limit and driver breaks. |
| [`CargoLoadingMultipleKnapsack`](#cargoloadingmultipleknapsack) | [Multiple knapsack](domains/knapsack.md) | Nine cargo lots compete for three containers. The program loads the most valuable combination. |
| [`WarehouseContainerPacking`](#warehousecontainerpacking) | [Container packing](domains/packing.md) | Eight boxes are placed in two containers in 3D, using as few containers as possible. |
| [`ClinicAppointmentBooking`](#clinicappointmentbooking) | [Resource booking](domains/booking.md) | Eight appointments share three rooms. One of them also needs a surgeon. The least important ones are left out when space runs short. |
| [`SteelRollCuttingStock`](#steelrollcuttingstock) | [Cutting stock](domains/cutting.md) | Beams of five lengths are cut from three kinds of stock with a 3 mm blade, wasting as little steel as possible. |

Click "Source" under an example to read its full code.

## PCB flexible job shop { #pcbmanufacturingjobshop }

A factory makes eight kinds of circuit board on five machines. Some steps can run on either
of two pick-and-place machines, and the solver chooses. The goal is to finish everything as
early as possible. The program prints the timetable of each machine.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.PcbManufacturingJobShop
```

??? example "Source: PcbManufacturingJobShop.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/PcbManufacturingJobShop.java"
    ```

## Hospital weekly nurse roster { #hospitalnursescheduling }

Twelve nurses cover 21 shifts over one week. The model uses skills, rest rules and personal
preferences, and looks for the roster with the lowest labor cost.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.HospitalNurseScheduling
```

??? example "Source: HospitalNurseScheduling.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/HospitalNurseScheduling.java"
    ```

## Barcelona parcel delivery { #barcelonadeliveryvrp }

Four vans deliver to 18 addresses. Customers have opening hours, vans have limited capacity
and a distance limit, and drivers take a break. The search builds first routes with the
`SAVINGS` method and improves them with guided local search.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.BarcelonaDeliveryVrp
```

??? example "Source: BarcelonaDeliveryVrp.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/BarcelonaDeliveryVrp.java"
    ```

## Cargo loading { #cargoloadingmultipleknapsack }

Nine cargo lots compete for three containers with weight limits. The program loads the
combination with the highest total value and lists what stays behind.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.CargoLoadingMultipleKnapsack
```

??? example "Source: CargoLoadingMultipleKnapsack.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/CargoLoadingMultipleKnapsack.java"
    ```

## Warehouse container loading { #warehousecontainerpacking }

Eight boxes are placed in two containers in three dimensions. The goal is to use as few
containers as possible. The program prints the position of every box.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.WarehouseContainerPacking
```

??? example "Source: WarehouseContainerPacking.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/WarehouseContainerPacking.java"
    ```

## Clinic appointment booking { #clinicappointmentbooking }

Eight appointments share three rooms, and one of them also needs a surgeon. Each appointment
has a penalty for being left out, and the solver keeps the total penalty as low as possible.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.ClinicAppointmentBooking
```

??? example "Source: ClinicAppointmentBooking.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/ClinicAppointmentBooking.java"
    ```

## Steel roll cutting { #steelrollcuttingstock }

Beams of five lengths are cut from three kinds of stock. The blade removes 3 mm at every cut.
The goal is to waste as little steel as possible.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.SteelRollCuttingStock
```

??? example "Source: SteelRollCuttingStock.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/SteelRollCuttingStock.java"
    ```

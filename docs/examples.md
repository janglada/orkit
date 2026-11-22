# Examples

Seven runnable programs, one per domain. They live in the test sources
(`src/test/java/io/github/janglada/orkit/examples`), so they are not shipped in the library jar.

Run one with Maven:

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.PcbManufacturingJobShop
```

| Example | Domain | What it shows |
|---|---|---|
| [`PcbManufacturingJobShop`](#pcbmanufacturingjobshop) | [Job-shop](domains/jobshop.md) | Eight board types on five machines with flexible pick-and-place assignment; minimizes makespan and prints a Gantt table. |
| [`HospitalNurseScheduling`](#hospitalnursescheduling) | [Shift scheduling](domains/scheduling.md) | Twelve nurses and 21 shifts over a week, with skills, rest rules, preferences and labor cost. |
| [`BarcelonaDeliveryVrp`](#barcelonadeliveryvrp) | [Vehicle routing](domains/routing.md) | Four vans and 18 stops with time windows, capacity, distance caps and driver breaks, using SAVINGS plus guided local search. |
| [`CargoLoadingMultipleKnapsack`](#cargoloadingmultipleknapsack) | [Multiple knapsack](domains/knapsack.md) | Nine cargo lots into three containers, maximizing loaded value. |
| [`WarehouseContainerPacking`](#warehousecontainerpacking) | [Container packing](domains/packing.md) | Eight boxes into two containers in 3D, minimizing containers used. |
| [`ClinicAppointmentBooking`](#clinicappointmentbooking) | [Resource booking](domains/booking.md) | Eight appointments on three rooms, one of them also needing a surgeon; minimizes the unscheduled penalty. |
| [`SteelRollCuttingStock`](#steelrollcuttingstock) | [Cutting stock](domains/cutting.md) | Beams of five lengths cut from three stock types with a 3 mm kerf, minimizing waste. |

## PCB flexible job shop { #pcbmanufacturingjobshop }

Eight board types on five machines with flexible pick-and-place assignment; minimizes makespan and prints a Gantt table.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.PcbManufacturingJobShop
```

??? example "Source: PcbManufacturingJobShop.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/PcbManufacturingJobShop.java"
    ```

## Hospital weekly nurse roster { #hospitalnursescheduling }

Twelve nurses and 21 shifts over a week, with skills, rest rules, preferences and labor cost.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.HospitalNurseScheduling
```

??? example "Source: HospitalNurseScheduling.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/HospitalNurseScheduling.java"
    ```

## Barcelona parcel delivery { #barcelonadeliveryvrp }

Four vans and 18 stops with time windows, capacity, distance caps and driver breaks, using SAVINGS plus guided local search.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.BarcelonaDeliveryVrp
```

??? example "Source: BarcelonaDeliveryVrp.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/BarcelonaDeliveryVrp.java"
    ```

## Cargo loading { #cargoloadingmultipleknapsack }

Nine cargo lots into three containers, maximizing loaded value.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.CargoLoadingMultipleKnapsack
```

??? example "Source: CargoLoadingMultipleKnapsack.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/CargoLoadingMultipleKnapsack.java"
    ```

## Warehouse container loading { #warehousecontainerpacking }

Eight boxes into two containers in 3D, minimizing containers used.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.WarehouseContainerPacking
```

??? example "Source: WarehouseContainerPacking.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/WarehouseContainerPacking.java"
    ```

## Clinic appointment booking { #clinicappointmentbooking }

Eight appointments on three rooms, one of them also needing a surgeon; minimizes the unscheduled penalty.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.ClinicAppointmentBooking
```

??? example "Source: ClinicAppointmentBooking.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/ClinicAppointmentBooking.java"
    ```

## Steel roll cutting { #steelrollcuttingstock }

Beams of five lengths cut from three stock types with a 3 mm kerf, minimizing waste.

```bash
mvn -q test-compile exec:java -Dexec.mainClass=io.github.janglada.orkit.examples.SteelRollCuttingStock
```

??? example "Source: SteelRollCuttingStock.java"

    ```java
    --8<-- "src/test/java/io/github/janglada/orkit/examples/SteelRollCuttingStock.java"
    ```

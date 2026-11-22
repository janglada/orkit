# Getting started

This page takes you from nothing to a solved problem in about five minutes.

## What you need

| You need | Details |
|---|---|
| Java 21 or newer | Any JDK from 21 up works. The library is tested on JDK 21 and 25. |
| Maven 3.6.3 or newer | Used to build the library and to manage dependencies. |
| Linux, macOS or Windows | Linux and macOS on Intel/AMD or ARM, Windows on Intel/AMD. |

**You do not install the solver yourself.** OR-Tools contains native code (compiled for each
operating system). It arrives automatically as a Maven dependency, and orkit loads the right
version the first time you use it.

??? note "Shipping a smaller application"
    By default the native code for all five platforms is downloaded, so your program runs
    anywhere. If you only ever deploy to one platform, you can exclude the other
    `ortools-<platform>` artifacts from the `ortools-java` dependency in your `pom.xml` to
    save space.

!!! tip "Add this JVM flag: `--enable-native-access=ALL-UNNAMED`"
    Newer Java versions print a warning when a library loads native code, and future versions
    will refuse to load it without this flag. It is safe on every Java version from 21 up, so
    add it now and forget about it:

    ```bash
    java --enable-native-access=ALL-UNNAMED -jar my-app.jar
    ```

## Install

orkit is not on Maven Central yet. For now, build it once on your machine:

```bash
git clone https://github.com/janglada/orkit.git
cd orkit
mvn install
```

Then add it to your own project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.janglada</groupId>
    <artifactId>orkit</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

??? note "Using the Java module system?"
    The module name is `io.github.janglada.orkit`. If you do not have a `module-info.java`
    file, you can ignore this.

## Your first model

**The problem.** You run a small moving company with two vans. Van 1 can carry 800 kg, van 2
can carry 500 kg. Five pieces of furniture are waiting, each with a weight and a value to you
(what the customer pays). Not everything fits. Which pieces should go in which van to earn
the most?

This kind of problem is called a *knapsack* problem: pick the most valuable things that fit.

Create a file `FirstModel.java`:

```java
import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.knapsack.BinAssignment;
import io.github.janglada.orkit.knapsack.MultipleKnapsackSolution;
import java.time.Duration;

public class FirstModel {
    public static void main(String[] args) {
        MultipleKnapsackSolution sol = Optimize.multipleKnapsack()
                .bin("Van-1", b -> b.capacity(800))                     // can carry 800 kg
                .bin("Van-2", b -> b.capacity(500))
                .item("Fridge",   i -> i.profit(600).weight(450))       // worth 600, weighs 450 kg
                .item("Sofa",     i -> i.profit(400).weight(380))
                .item("Piano",    i -> i.profit(900).weight(700))
                .item("Bookcase", i -> i.profit(150).weight(120))
                .item("Mattress", i -> i.profit(200).weight(90))
                .solver()
                .timeLimit(Duration.ofSeconds(10))
                .solve();

        System.out.println(sol.status() + ", total value " + sol.totalProfit());
        for (BinAssignment van : sol.assignments()) {
            System.out.printf("%s carries %s: %d of %d kg%n",
                    van.bin(), van.items(), van.usedCapacity(), van.capacity());
        }
        System.out.println("Stays behind: " + sol.unassignedItems());
    }
}
```

Run it. It prints:

```text
OPTIMAL, total value 1700
Van-1 carries [Piano, Mattress]: 790 of 800 kg
Van-2 carries [Fridge]: 450 of 500 kg
Stays behind: [Sofa, Bookcase]
```

The solver tried the combinations for you and proved that 1700 is the best total you can
reach. That is what `OPTIMAL` means.

## What just happened

Every orkit program has the same three steps.

### 1. Describe the problem

```java
Optimize.multipleKnapsack()
        .bin("Van-1", b -> b.capacity(800))
        .item("Fridge", i -> i.profit(600).weight(450))
```

- `Optimize` is the starting point. It has one method per kind of problem:
  `multipleKnapsack()`, `jobShop()`, `scheduling()`, and so on.
- Each thing in your problem gets a **name** (`"Van-1"`, `"Fridge"`) and a small block that
  sets its **details** (`b -> b.capacity(800)`). You use the same names later to read the
  answer.

### 2. Solve it

```java
        .solver()
        .timeLimit(Duration.ofSeconds(10))
        .solve();
```

- `solver()` says "I am done describing, now let me set up the search".
- `timeLimit(...)` is the longest you are willing to wait. Small problems finish much sooner.
- `solve()` runs the search and returns the answer.

### 3. Read the answer

```java
sol.status();           // OPTIMAL
sol.totalProfit();      // 1700
sol.assignments();      // one entry per van
sol.unassignedItems();  // [Sofa, Bookcase]
```

The answer is an ordinary Java object. Always look at `status()` first:

| Status | In plain words |
|---|---|
| `OPTIMAL` | This is the best possible plan, and that is proven. |
| `FEASIBLE` | This plan is valid, but a better one may exist. Usually the time ran out. |
| `INFEASIBLE` | No plan can satisfy all your rules at once. |
| `UNKNOWN` | Time ran out before the solver found anything. Give it more time. |

## Try changing it

The best way to learn is to change the model and run it again.

**Buy a bigger van.** Change `Van-2` to `capacity(900)`. Now more fits, and the total value
goes up.

**Ask a different question.** Where did a single item end up?

```java
sol.binOf("Piano");      // Optional[Van-1]
sol.binOf("Sofa");       // Optional.empty: it was left behind
```

**Make a mistake on purpose.** Use the same van name twice:

```java
Optimize.multipleKnapsack()
        .bin("Van-1", b -> b.capacity(800))
        .bin("Van-1", b -> b.capacity(500));
```

You get a clear error instead of a strange answer:

```text
ModelException: Duplicate bin id: 'Van-1'.
```

## Where to go next

- [Core concepts](concepts.md) explains the three steps in more depth.
- [Domains](../domains/index.md) has one page per kind of problem, each starting with a
  quick start you can copy.
- [Examples](../examples.md) has seven larger, complete programs.

## Common first-time problems

**"A warning about native access appears when I run my program."**
Add `--enable-native-access=ALL-UNNAMED` to the `java` command (see the tip above). The
program works either way; the flag only removes the warning.

**"Maven cannot find `io.github.janglada:orkit`."**
You have not run `mvn install` in the orkit folder yet, or the version in your `pom.xml` does
not match `0.1.0-SNAPSHOT`.

**"The result is `UNKNOWN`."**
The time limit was too short for the solver to find any plan. Raise `timeLimit(...)`.

**"The compiler says `reference to Goals is ambiguous`."**
Every domain has its own `Goals` class. If one file imports two domains with `*`, Java cannot
tell them apart. Import the one you need by its full name, for example
`import io.github.janglada.orkit.jobshop.Goals;`.

## For contributors

### Build and test the library

```bash
mvn verify
```

This compiles the code, runs about 500 tests, and builds the Javadoc. The build is strict on
purpose: it fails on any compiler warning, on any Javadoc error, and when test coverage drops
below 97% of lines or 90% of branches. While you are working on a change, `mvn test` is
faster.

### Build this documentation

```bash
docs/build.sh          # writes the site to site/index.html
docs/build.sh serve    # live preview on http://127.0.0.1:8000
```

The script sets up everything it needs (a Python environment with
[MkDocs](https://www.mkdocs.org/) and the
[Material](https://squidfunk.github.io/mkdocs-material/) theme), generates the Javadoc, and
builds the site. The built site also works when you open `site/index.html` straight from
disk.

The public site at <https://janglada.github.io/orkit/> is rebuilt automatically when changes
reach the `master` branch.

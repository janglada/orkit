# Getting started

## Requirements

| Requirement | Notes |
|---|---|
| Java 21 or newer | The library uses records, sealed interfaces and pattern-matching `switch`. CI runs on JDK 21 and 25. |
| Maven 3.6.3 or newer | Enforced by the build. |
| A supported platform | Linux (x86-64, arm64), macOS (x86-64, arm64), Windows (x86-64). |

**Native libraries need no setup.** `ortools-java` depends on the OR-Tools native libraries
for every supported platform, and orkit loads the right one the first time you create a
builder. To ship a smaller application for one platform, exclude the other
`ortools-<platform>` artifacts from `ortools-java`.

!!! note "JDK 24 and newer"
    OR-Tools loads its JNI library with `System.loadLibrary`, which recent JDKs flag as a
    restricted method. Run your application with `--enable-native-access=ALL-UNNAMED` to
    silence the warning; later JDKs will require it.

## Installation

orkit is not yet published to Maven Central. Install it into your local repository:

```bash
git clone https://github.com/janglada/orkit.git
cd orkit
mvn install
```

Then depend on it:

```xml
<dependency>
    <groupId>io.github.janglada</groupId>
    <artifactId>orkit</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The jar declares `Automatic-Module-Name: io.github.janglada.orkit` for module-path users.

## Your first model

A multiple-knapsack problem: four cargo items, two bins, maximize the profit of what fits.

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
    System.out.printf("  %s: %s (load %d/%d)%n", a.bin(), a.items(), a.usedCapacity(), a.capacity());
}
```

The same three steps apply to every domain:

1. **Describe** the problem with a builder from `Optimize`.
2. **Configure** a solver with `solver()`: time limit, workers, callbacks.
3. **Read** the typed result: `status()`, `objective()`, and domain-specific accessors.

## Building from source

```bash
mvn verify
```

`verify` compiles with every lint warning treated as an error, runs about 500 JUnit tests,
enforces a JaCoCo coverage floor (97% of lines, 90% of branches), and builds the sources and
Javadoc jars with doclint on. `mvn test` alone is faster while iterating.

## Building this documentation

The published site lives at <https://janglada.github.io/orkit/>; the `Docs` GitHub Actions
workflow rebuilds it and pushes it to the `gh-pages` branch on every relevant push to `master`.

The site is built with [MkDocs](https://www.mkdocs.org/) and the
[Material](https://squidfunk.github.io/mkdocs-material/) theme, and embeds the generated
Javadoc as its API reference.

```bash
docs/build.sh          # writes site/index.html
docs/build.sh serve    # live preview on http://127.0.0.1:8000
```

The script creates a virtual environment with the pinned versions in `docs/requirements.txt`,
generates the Javadoc for the public packages, and runs `mkdocs build --strict`. The first
build downloads two small scripts the theme needs and bundles them, so the built site also
works when opened straight from the file system. Its only external request is the theme's
lookup of the repository's stars and latest release on the GitHub API.

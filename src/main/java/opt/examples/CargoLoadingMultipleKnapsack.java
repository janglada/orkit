package opt.examples;

import opt.Optimize;
import opt.knapsack.BinAssignment;
import opt.knapsack.MultipleKnapsackSolution;

import java.time.Duration;

/**
 * Cargo loading — assign freight to multiple containers/trucks to maximize total
 * shipped value without exceeding any container's weight capacity.
 *
 * <p>Demonstrates: multiple knapsacks, profit (value) maximization, per-bin
 * capacity constraints, items that may be left behind when capacity is tight.
 *
 * <p>Containers (weight capacity, in tons):
 * <ul>
 *   <li>C1 — 15 t</li>
 *   <li>C2 — 20 t</li>
 *   <li>C3 — 10 t</li>
 * </ul>
 * Each cargo item has a declared value (profit) and weight; the solver picks which
 * container (if any) holds each item so that total shipped value is maximized.
 *
 * Run: mvn exec:java -Dexec.mainClass=opt.examples.CargoLoadingMultipleKnapsack
 */
public class CargoLoadingMultipleKnapsack {

    /**
     * Builds the cargo-loading multiple-knapsack model, solves it with CP-SAT, and
     * prints the per-container assignments, total shipped profit, and any items
     * left behind due to capacity.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║  Cargo Loading — Multiple Knapsack          ║");
        System.out.println("╚═════════════════════════════════════════════╝");

        MultipleKnapsackSolution solution = Optimize.multipleKnapsack()
                .bin("C1", b -> b.capacity(15))
                .bin("C2", b -> b.capacity(20))
                .bin("C3", b -> b.capacity(10))

                .item("Steel-Coils",   i -> i.profit(60).weight(12))
                .item("Electronics",   i -> i.profit(90).weight(6))
                .item("Machinery",     i -> i.profit(120).weight(10))
                .item("Textiles",      i -> i.profit(40).weight(5))
                .item("Chemicals",     i -> i.profit(70).weight(8))
                .item("Furniture",     i -> i.profit(50).weight(7))
                .item("Foodstuff",     i -> i.profit(30).weight(4))
                .item("Oversize-Rig",  i -> i.profit(150).weight(18))   // fits only C2
                .item("Pharma",        i -> i.profit(80).weight(3))

                .solver()
                .timeLimit(Duration.ofSeconds(10))
                .workers(8)
                .solve();

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus      : %s%n", solution.status());
        System.out.printf("TotalProfit : %d%n%n", solution.totalProfit());

        System.out.println("── Container assignments ─────────────────────────────────────");
        System.out.printf("%-5s  %-14s  %-8s  %-8s%n", "Bin", "Items", "Load", "Profit");
        System.out.println("-".repeat(40));
        for (BinAssignment a : solution.assignments()) {
            System.out.printf("%-5s  %-14s  %-8d  %-8d%n",
                    a.bin(),
                    a.items().isEmpty() ? "-" : String.join(",", a.items()),
                    a.usedCapacity(),
                    a.profit());
        }

        if (!solution.unassignedItems().isEmpty()) {
            System.out.printf("%nLeft behind: %s%n", solution.unassignedItems());
        }
    }
}

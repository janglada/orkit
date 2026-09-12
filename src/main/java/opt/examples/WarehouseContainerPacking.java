package opt.examples;

import com.google.ortools.Loader;
import opt.packing.ContainerAssignment;
import opt.packing.Goals;
import opt.packing.PackingBuilder;
import opt.packing.PackingSolution;

import java.time.Duration;

/**
 * Warehouse container loading — pack boxes into the minimum number of containers while
 * respecting 3D non-overlap placement and per-container weight limits.
 *
 * <p>Demonstrates: 3D bin packing, container minimization, axis-aligned placement with
 * no-overlap constraints, boxes that may be left unassigned when they do not fit.
 *
 * <p>Containers (interior L x W x H, max weight):
 * <ul>
 *   <li>C1 — 12 x 8 x 6, max weight 30</li>
 *   <li>C2 — 12 x 8 x 6, max weight 30</li>
 * </ul>
 * Each box has a length, width, height, and weight; the solver chooses a container and a
 * placement (x, y, z origin) for every box that fits, minimizing the containers used.
 *
 * Run: mvn exec:java -Dexec.mainClass=opt.examples.WarehouseContainerPacking
 */
public class WarehouseContainerPacking {

    /**
     * Builds the container-loading model, solves it with CP-SAT, and prints the
     * per-container placements (with box coordinates) and any boxes left behind.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║  Warehouse Container Loading — 3D Bin Pack  ║");
        System.out.println("╚═════════════════════════════════════════════╝");
        Loader.loadNativeLibraries();

        PackingSolution solution = new PackingBuilder()
                .goal(Goals.minimizeContainersUsed())
                .container("C1", c -> c.length(12).width(8).height(6).maxWeight(30))
                .container("C2", c -> c.length(12).width(8).height(6).maxWeight(30))

                .box("Pallet-A",   b -> b.length(4).width(4).height(3).weight(8))
                .box("Pallet-B",   b -> b.length(4).width(4).height(3).weight(8))
                .box("Crate-C",    b -> b.length(3).width(2).height(2).weight(4))
                .box("Crate-D",    b -> b.length(3).width(2).height(2).weight(4))
                .box("Box-E",      b -> b.length(2).width(2).height(2).weight(2))
                .box("Box-F",      b -> b.length(2).width(2).height(2).weight(2))
                .box("Box-G",      b -> b.length(2).width(1).height(1).weight(1))
                .box("Oversize-H", b -> b.length(13).width(1).height(1).weight(1))   // fits nowhere
                .solver()
                .timeLimit(Duration.ofSeconds(10))
                .workers(8)
                .solve();

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus         : %s%n", solution.status());
        System.out.printf("Containers used : %d%n", solution.containersUsed());
        System.out.printf("Objective       : %s%n%n",
                solution.objective().isPresent() ? solution.objective().getAsDouble() : "n/a");

        System.out.println("── Container placements ──────────────────────────────────────");
        System.out.printf("%-5s  %-10s  %-18s  %-10s  %-10s%n",
                "Cont", "Boxes", "Placements", "UsedVol", "UsedWt");
        System.out.println("-".repeat(60));
        for (ContainerAssignment a : solution.assignments()) {
            String placements = a.boxes().isEmpty() ? "-" :
                    a.boxes().stream()
                            .map(p -> p.boxId() + "@(" + p.x() + "," + p.y() + "," + p.z() + ")")
                            .reduce((x, y) -> x + " " + y).orElse("-");
            System.out.printf("%-5s  %-10d  %-18s  %-10d  %-10d%n",
                    a.containerId(),
                    a.boxes().size(),
                    placements,
                    a.usedVolume(),
                    a.usedWeight());
        }

        if (!solution.unassignedBoxes().isEmpty()) {
            System.out.printf("%nLeft behind: %s%n", solution.unassignedBoxes());
        }
    }
}

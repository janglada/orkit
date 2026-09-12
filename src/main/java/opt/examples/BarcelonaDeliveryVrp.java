package opt.examples;

import opt.Optimize;
import opt.routing.*;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * Morning parcel delivery across Barcelona — 4 vehicles, 18 stops, time windows.
 *
 * Demonstrates: realistic lat/lon coordinates, multiple vehicles with different
 * capacities, time windows in two cohorts, haversine distances, SAVINGS + GLS.
 *
 * Depot: Mercabarna logistics hub (Zona Franca)
 * Stops: hotels, supermarkets, and restaurant groups across Barcelona neighborhoods.
 *
 * Run: mvn exec:java -Dexec.mainClass=opt.examples.BarcelonaDeliveryVrp
 */
public class BarcelonaDeliveryVrp {

    /**
     * Builds the Barcelona delivery VRP model, solves it with the {@code SAVINGS}
     * first solution followed by guided local search, and prints the routes,
     * vehicle utilization, and any stops that could not be assigned.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║   Barcelona Morning Delivery — Vehicle Routing    ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        RoutingPlan plan = Optimize.vehicleRouting()

                // Mercabarna logistics hub — Zona Franca
                .depot("Mercabarna", 41.3473, 2.1260)

                // Fleet: 2 large vans + 2 small vans
                .vehicle("LargeVan-1",  v -> v.capacity(25).maxDistanceMeters(60_000))
                .vehicle("LargeVan-2",  v -> v.capacity(25).maxDistanceMeters(60_000))
                .vehicle("SmallVan-1",  v -> v.capacity(15).maxDistanceMeters(50_000))
                .vehicle("SmallVan-2",  v -> v.capacity(15).maxDistanceMeters(50_000))

                // ── Morning wave (08:00-11:00): hotels and high-end restaurants ──
                .stop("Hotel Arts",         s -> s.at(41.3858, 2.1968).demand(6)
                        .serviceTime(Duration.ofMinutes(15))
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(11, 0)))
                .stop("Hotel W Barcelona",   s -> s.at(41.3736, 2.1884).demand(5)
                        .serviceTime(Duration.ofMinutes(15))
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(11, 0)))
                .stop("Hotel Mandarin",      s -> s.at(41.3936, 2.1612).demand(4)
                        .serviceTime(Duration.ofMinutes(10))
                        .openFrom(LocalTime.of(8, 30)).closeAt(LocalTime.of(11, 0)))
                .stop("Hotel Majestic",      s -> s.at(41.3923, 2.1651).demand(3)
                        .serviceTime(Duration.ofMinutes(10))
                        .openFrom(LocalTime.of(8, 30)).closeAt(LocalTime.of(10, 30)))
                .stop("Hotel Claris",        s -> s.at(41.3946, 2.1630).demand(3)
                        .serviceTime(Duration.ofMinutes(10))
                        .openFrom(LocalTime.of(8, 0)).closeAt(LocalTime.of(10, 30)))

                // ── Midday wave (11:00-14:00): supermarkets and caterers ──────────
                .stop("Caprabo Eixample",    s -> s.at(41.3888, 2.1524).demand(8)
                        .serviceTime(Duration.ofMinutes(20))
                        .openFrom(LocalTime.of(11, 0)).closeAt(LocalTime.of(14, 0)))
                .stop("Mercadona Gràcia",    s -> s.at(41.4051, 2.1561).demand(7)
                        .serviceTime(Duration.ofMinutes(20))
                        .openFrom(LocalTime.of(11, 0)).closeAt(LocalTime.of(14, 0)))
                .stop("Catering Poblenou",   s -> s.at(41.4015, 2.1978).demand(6)
                        .serviceTime(Duration.ofMinutes(15))
                        .openFrom(LocalTime.of(11, 30)).closeAt(LocalTime.of(13, 30)))
                .stop("Supermercado Sants",  s -> s.at(41.3740, 2.1385).demand(5)
                        .serviceTime(Duration.ofMinutes(15))
                        .openFrom(LocalTime.of(11, 0)).closeAt(LocalTime.of(14, 0)))

                // ── Flexible stops (no time window — delivered whenever convenient) ─
                .stop("Bodega Barceloneta",  s -> s.at(41.3802, 2.1894).demand(4)
                        .serviceTime(Duration.ofMinutes(10)))
                .stop("Bar Raval",           s -> s.at(41.3793, 2.1644).demand(3)
                        .serviceTime(Duration.ofMinutes(8)))
                .stop("Restaurante Les Corts",s-> s.at(41.3844, 2.1317).demand(4)
                        .serviceTime(Duration.ofMinutes(10)))
                .stop("Café Sarrià",         s -> s.at(41.4007, 2.1339).demand(2)
                        .serviceTime(Duration.ofMinutes(8)))
                .stop("Mercat Sant Martí",   s -> s.at(41.4001, 2.1967).demand(5)
                        .serviceTime(Duration.ofMinutes(12)))
                .stop("Colmado Nou Barris",  s -> s.at(41.4380, 2.1793).demand(3)
                        .serviceTime(Duration.ofMinutes(8)))
                .stop("Pastisseria Horta",   s -> s.at(41.4259, 2.1712).demand(2)
                        .serviceTime(Duration.ofMinutes(5)))
                .stop("Forn de Pa Gràcia",   s -> s.at(41.4022, 2.1544).demand(2)
                        .serviceTime(Duration.ofMinutes(5)))
                .stop("Xarcuteria Eixample", s -> s.at(41.3864, 2.1502).demand(3)
                        .serviceTime(Duration.ofMinutes(8)))

                .distances(DistanceSource.haversine())

                .goal(Goals.totalDistance())

                .solver()
                .timeLimit(Duration.ofSeconds(30))
                .firstSolution(Strategy.SAVINGS)
                .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
                .solve();

        // ── Results ───────────────────────────────────────────────────────────
        System.out.printf("%nStatus : %s%n", plan.status());

        if (!plan.isFeasible()) {
            System.out.println("No solution found.");
            return;
        }

        plan.objective().ifPresent(d ->
            System.out.printf("Total distance : %.1f km across all vehicles%n", d / 1000.0));

        System.out.println();

        for (Route r : plan.routes()) {
            if (r.stops().isEmpty()) continue;
            System.out.printf("%-14s (load %2d/%-2d): depot → %s → depot  [%.1f km]%n",
                    r.vehicle(), r.load(),
                    r.vehicle().startsWith("Large") ? 25 : 15,
                    String.join(" → ", r.stops()),
                    r.distanceMeters() / 1000.0);
        }

        if (!plan.unassignedStops().isEmpty()) {
            System.out.println("\nUnassigned stops: " + plan.unassignedStops());
        } else {
            System.out.println("\nAll 18 stops successfully assigned.");
        }

        // Per-vehicle statistics
        System.out.println("\n── Vehicle utilization ──────────────────────────────────────────────");
        System.out.printf("%-14s  %-6s  %-8s  %-6s%n", "Vehicle", "Stops", "Distance", "Load");
        System.out.println("-".repeat(42));
        for (Route r : plan.routes()) {
            System.out.printf("%-14s  %-6d  %-8.1f  %-6d%n",
                    r.vehicle(), r.stops().size(), r.distanceMeters() / 1000.0, r.load());
        }
    }
}

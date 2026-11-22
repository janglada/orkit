package io.github.janglada.orkit.routing;

import io.github.janglada.orkit.Optimize;
import io.github.janglada.orkit.db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-driven last-mile delivery example for the vehicle routing solver.
 *
 * <p>Loads a realistic parcel-courier schema from {@code db/routing.sql} (two depots
 * — Mercabarna (west) and Poblenou (east) — a heterogeneous fleet of 4 vans assigned
 * to depots, and 16 customer stops across Barcelona with demands, service times, and
 * morning / midday / flexible time-window cohorts), builds the routing model via the
 * fluent DSL, solves it, and asserts the structural invariants a dispatcher would
 * rely on — including that each vehicle's route departs from its assigned depot.
 *
 * <p>Uses {@link Strategy#PATH_CHEAPEST_ARC} as the first-solution strategy: it is
 * the most reliable packer for tight, time-windowed VRPs (the SAVINGS heuristic can
 * fail to find an initial feasible packing here even though one exists, which the
 * routing solver then misreports as infeasible).
 *
 * <p>The test also prints the solved routes (status, objective, per-vehicle tour,
 * depot, load, distance) so it doubles as a runnable example.
 *
 * <p>Run: {@code mvn test -Dtest=RoutingDbExampleTest}
 */
class RoutingDbExampleTest {

    @Test
    void solveLastMileDeliveryFromDatabase() throws SQLException {
        try (TestDatabase db = TestDatabase.load("db/routing.sql")) {

            // ── Load depots (multiple; first is the default) ─────────────────
            Map<String, double[]> depotCoords = new HashMap<>();
            RoutingBuilder builder = Optimize.vehicleRouting();
            for (Map<String, Object> d : db.select("SELECT * FROM depot ORDER BY id")) {
                String did = TestDatabase.asString(d, "id");
                double lat = ((Number) d.get("lat")).doubleValue();
                double lon = ((Number) d.get("lon")).doubleValue();
                depotCoords.put(did, new double[]{lat, lon});
                builder = builder.depot(did, lat, lon);
            }

            // ── Load fleet, keyed by id → capacity / assigned depot ───────────
            Map<String, Integer> capacityOf = new HashMap<>();
            Map<String, String> vehicleDepot = new HashMap<>();
            for (Map<String, Object> v : db.select("SELECT * FROM vehicle ORDER BY id")) {
                String vid = TestDatabase.asString(v, "id");
                int capacity = TestDatabase.asInt(v, "capacity");
                long maxDistance = TestDatabase.asLong(v, "max_distance_m");
                capacityOf.put(vid, capacity);

                // depot_id is nullable; a null falls back to the default (first) depot.
                Object depotIdVal = v.get("depot_id");
                final String depotId = depotIdVal == null ? null : depotIdVal.toString();
                if (depotId != null) {
                    vehicleDepot.put(vid, depotId);
                }

                builder = builder.vehicle(vid, vc -> {
                    vc.capacity(capacity).maxDistanceMeters(maxDistance);
                    if (depotId != null) {
                        vc.startsAt(depotId);
                    }
                });
            }

            // ── Load stops, building demand totals and time windows ──────────
            int totalDemand = 0;
            int stopCount = 0;
            for (Map<String, Object> s : db.select("SELECT * FROM stop ORDER BY id")) {
                String sid = TestDatabase.asString(s, "id");
                double lat = ((Number) s.get("lat")).doubleValue();
                double lon = ((Number) s.get("lon")).doubleValue();
                int demand = TestDatabase.asInt(s, "demand");
                int serviceMin = TestDatabase.asInt(s, "service_min");
                int openMin = TestDatabase.asInt(s, "open_min");
                int closeMin = TestDatabase.asInt(s, "close_min");
                totalDemand += demand;
                stopCount++;

                final int fDemand = demand;
                final int fServiceMin = serviceMin;
                final int fOpenMin = openMin;
                final int fCloseMin = closeMin;

                builder = builder.stop(sid, sc -> {
                    sc.at(lat, lon).demand(fDemand)
                            .serviceTime(Duration.ofMinutes(fServiceMin));
                    // 0 / 1440 means "no window" → omit open/close (flexible).
                    if (!(fOpenMin == 0 && fCloseMin == 1440)) {
                        sc.openFrom(LocalTime.ofSecondOfDay(fOpenMin * 60L))
                                .closeAt(LocalTime.ofSecondOfDay(fCloseMin * 60L));
                    }
                });
            }

            // ── Solve: haversine distances, minimize total distance ──────────
            RoutingPlan plan = builder
                    .distances(DistanceSource.haversine())
                    .goal(Goals.totalDistance())
                    .solver()
                    .timeLimit(Duration.ofSeconds(5))
                    .firstSolution(Strategy.PATH_CHEAPEST_ARC)
                    .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
                    .solve();

            // ── Assertions ───────────────────────────────────────────────────
            assertThat(plan.isFeasible())
                    .as("model must be feasible — total demand %d fits fleet capacity", totalDemand)
                    .isTrue();
            assertThat(plan.unassignedStops())
                    .as("every customer stop must be served")
                    .isEmpty();

            // Per-vehicle capacity respected
            for (Route r : plan.routes()) {
                int cap = capacityOf.get(r.vehicle());
                assertThat(r.load())
                        .as("vehicle %s load %d exceeds capacity %d", r.vehicle(), r.load(), cap)
                        .isLessThanOrEqualTo(cap);
            }

            // Each vehicle's route departs from its assigned depot.
            for (Route r : plan.routes()) {
                String expectedDepot = vehicleDepot.get(r.vehicle());
                if (expectedDepot != null) {
                    assertThat(r.depotId())
                            .as("vehicle %s should depart from depot %s", r.vehicle(), expectedDepot)
                            .isEqualTo(expectedDepot);
                }
                assertThat(depotCoords).containsKey(r.depotId());
            }

            // Total served demand equals sum of all stop demands
            long servedDemand = plan.routes().stream().mapToLong(Route::load).sum();
            assertThat(servedDemand)
                    .as("served demand must equal total stop demand")
                    .isEqualTo(totalDemand);

            // ── Print routes (doubles as a runnable example) ─────────────────
            System.out.println("╔═══════════════════════════════════════════════════╗");
            System.out.println("║  Barcelona Last-Mile Delivery (multi-depot VRP) ║");
            System.out.println("╚═══════════════════════════════════════════════════╝");
            System.out.printf("%nStatus : %s%n", plan.status());
            plan.objective().ifPresent(d ->
                    System.out.printf("Total distance : %.1f km across all vehicles%n", d / 1000.0));
            System.out.println();

            for (Route r : plan.routes()) {
                if (r.stops().isEmpty()) continue;
                System.out.printf("%-12s (load %2d/%-2d): %s → %s → %s  [%.1f km]%n",
                        r.vehicle(), r.load(), capacityOf.get(r.vehicle()),
                        r.depotId(), String.join(" → ", r.stops()), r.depotId(),
                        r.distanceMeters() / 1000.0);
            }

            System.out.println("\n── Vehicle utilization ──────────────────────────────────────────────");
            System.out.printf("%-12s  %-10s  %-6s  %-8s  %-6s%n", "Vehicle", "Depot", "Stops", "Distance", "Load");
            System.out.println("-".repeat(52));
            for (Route r : plan.routes()) {
                System.out.printf("%-12s  %-10s  %-6d  %-8.1f  %-6d%n",
                        r.vehicle(), r.depotId(), r.stops().size(), r.distanceMeters() / 1000.0, r.load());
            }
            System.out.printf("%nAll %d stops successfully assigned (total demand %d).%n",
                    stopCount, totalDemand);
        }
    }
}

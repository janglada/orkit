package opt;

import opt.jobshop.JobShopSchedule;
import opt.jobshop.ScheduledOperation;
import opt.routing.DistanceSource;
import opt.routing.Metaheuristic;
import opt.routing.Route;
import opt.routing.RoutingPlan;
import opt.routing.Strategy;
import opt.scheduling.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static opt.routing.Goals.totalDistance;
import static opt.scheduling.Goals.*;
import static opt.scheduling.Rules.*;

public class SmokeTest {

    public static void main(String[] args) {
        testScheduling();
        testJobShop();
        testRouting();
    }

    static void testScheduling() {
        System.out.println("=== Employee Scheduling ===");

        EmployeeRoster roster = Optimize.scheduling()
                .horizon(LocalDate.of(2026, 7, 6), 7)

                .employee("alice", e -> e
                        .skills("RN", "ICU")
                        .costPerHour(45)
                        .availableOn(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        .maxHoursPerWeek(40))
                .employee("bob", e -> e
                        .skills("RN")
                        .costPerHour(38)
                        .maxHoursPerWeek(36))

                .shift("mon-day", s -> s
                        .on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(7, 0)).to(LocalTime.of(15, 0))
                        .needs(1).withSkill("RN"))
                .shift("mon-night", s -> s
                        .on(DayOfWeek.MONDAY)
                        .from(LocalTime.of(15, 0)).to(LocalTime.of(23, 0))
                        .needs(1).withSkill("ICU"))

                .constrain(oneShiftPerEmployeePerDay())
                .constrain(minRestBetweenShifts(Duration.ofHours(11)))

                .prefer(balanceWorkloadAcrossEmployees())
                .minimize(totalLaborCost())

                .solver()
                .timeLimit(Duration.ofSeconds(5))
                .workers(4)
                .solve();

        System.out.println("Status: " + roster.status());
        if (roster.isFeasible()) {
            roster.assignments().forEach(a ->
                    System.out.printf("  %-6s → %s%n", a.employee(), a.shift()));
            roster.objective().ifPresent(c -> System.out.printf("  Cost: %.0f min·$/h%n", c));
        }
    }

    static void testJobShop() {
        System.out.println("\n=== Job Shop ===");

        JobShopSchedule schedule = Optimize.jobShop()
                .machines("M1", "M2", "M3")

                .job("J1", j -> j
                        .operation().on("M1").takes(3)
                        .then().on("M2").takes(2)
                        .then().on("M3").takes(2))
                .job("J2", j -> j
                        .operation().on("M2").takes(2)
                        .then().on("M1").takes(4)
                        .then().on("M3").takes(3))
                .job("J3", j -> j
                        .operation().on("M3").takes(4)
                        .then().on("M2").takes(3))

                .minimizeMakespan()

                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        System.out.println("Status:   " + schedule.status());
        System.out.println("Makespan: " + schedule.makespan());
        for (String machine : List.of("M1", "M2", "M3")) {
            for (ScheduledOperation op : schedule.timelineOf(machine)) {
                System.out.printf("  %s | %-3s op%d : [%d, %d)%n",
                        machine, op.job(), op.index(), op.start(), op.end());
            }
        }
    }

    static void testRouting() {
        System.out.println("\n=== Vehicle Routing ===");

        RoutingPlan plan = Optimize.vehicleRouting()
                .depot("warehouse", 41.3900, 2.1600)

                .vehicle("van-1", v -> v.capacity(100).maxDistanceMeters(200_000))
                .vehicle("van-2", v -> v.capacity(80))

                .stop("A", s -> s.at(41.40, 2.17).demand(20)
                        .serviceTime(Duration.ofMinutes(10))
                        .openFrom(LocalTime.of(9, 0)).closeAt(LocalTime.of(12, 0)))
                .stop("B", s -> s.at(41.38, 2.18).demand(35))
                .stop("C", s -> s.at(41.41, 2.15).demand(15))

                .distances(DistanceSource.haversine())

                .minimize(totalDistance())

                .solver()
                .timeLimit(Duration.ofSeconds(10))
                .firstSolution(Strategy.SAVINGS)
                .improveWith(Metaheuristic.GUIDED_LOCAL_SEARCH)
                .solve();

        System.out.println("Status: " + plan.status());
        for (Route r : plan.routes()) {
            System.out.printf("  %s: %s  (%.1f km, load %d)%n",
                    r.vehicle(),
                    String.join(" → ", r.stops()),
                    r.distanceMeters() / 1000.0,
                    r.load());
        }
        if (!plan.unassignedStops().isEmpty()) {
            System.out.println("  Unassigned: " + plan.unassignedStops());
        }
    }
}

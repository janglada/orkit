package opt.booking;

import opt.ModelException;
import opt.Optimize;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deeper coverage of booking: validation guards, buffers vs. bounds, occupied-interval invariants. */
class BookingFeaturesTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);

    /** Occupied intervals (duration + buffer) never overlap on a resource; reported ends exclude the buffer. */
    static void assertNoOccupiedOverlap(BookingSolution sol, Map<String, Duration> bufferOf) {
        for (String rid : sol.assignments().stream().map(AppointmentAssignment::resourceId).distinct().toList()) {
            List<AppointmentAssignment> on = sol.assignmentsOn(rid).stream()
                    .sorted(Comparator.comparing(AppointmentAssignment::start)).toList();
            for (int i = 0; i + 1 < on.size(); i++) {
                LocalDateTime occupiedEnd = on.get(i).end().plus(bufferOf.getOrDefault(on.get(i).appointmentId(), Duration.ZERO));
                assertThat(occupiedEnd).as("%s then %s on %s", on.get(i).appointmentId(), on.get(i + 1).appointmentId(), rid)
                        .isBeforeOrEqualTo(on.get(i + 1).start());
            }
        }
    }

    @Test
    void validationGuards() {
        assertThatThrownBy(() -> Optimize.booking().horizon(null, 1)).isInstanceOf(ModelException.class).hasMessageContaining("horizon start");
        assertThatThrownBy(() -> Optimize.booking().horizon(MONDAY, 0)).isInstanceOf(ModelException.class).hasMessageContaining("days");
        assertThatThrownBy(() -> Optimize.booking().resource("R", r -> r.available((TimeSlot) null))).isInstanceOf(ModelException.class).hasMessageContaining("slot");
        assertThatThrownBy(() -> Optimize.booking().resource("R", r -> r.available(new DayOfWeek[0], LocalTime.of(8, 0), LocalTime.of(9, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("at least one day");
        assertThatThrownBy(() -> Optimize.booking().resource("R", r -> r.availableDaily(null, LocalTime.of(9, 0))))
                .isInstanceOf(ModelException.class).hasMessageContaining("must not be null");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.buffer(Duration.ofMinutes(-5))))
                .isInstanceOf(ModelException.class).hasMessageContaining("buffer()");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.requiresAnyOf()))
                .isInstanceOf(ModelException.class).hasMessageContaining("requiresAnyOf()");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.requiresAnyOf("R")))
                .isInstanceOf(ModelException.class).hasMessageContaining(".duration(");
        assertThatThrownBy(() -> Optimize.booking().resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R")).solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("horizon(...) must be set");
        assertThatThrownBy(() -> Optimize.booking().horizon(MONDAY, 1).resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R").earliest(MONDAY.minusDays(1).atTime(9, 0))).solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("before horizon start");
        assertThatThrownBy(() -> Optimize.booking().horizon(MONDAY, 1).resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R").latest(MONDAY.plusDays(2).atTime(9, 0))).solver())
                .isInstanceOf(ModelException.class).hasMessageContaining("after horizon end");
        assertThatThrownBy(() -> Optimize.booking().horizon(MONDAY, 1).resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("NOPE")).solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown resource 'NOPE'");
    }

    @Test
    void multipleDaysArrayOverloadAndSlotMergingWithTouchingWindows() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 7)
                .resource("R", r -> r
                        .available(new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.FRIDAY}, LocalTime.of(8, 0), LocalTime.of(9, 0))
                        .available(new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.FRIDAY}, LocalTime.of(9, 0), LocalTime.of(10, 0)))  // touches => merged 08-10
                .appointment("A", a -> a.duration(Duration.ofMinutes(120)).requiresAnyOf("R"))
                .appointment("B", a -> a.duration(Duration.ofMinutes(120)).requiresAnyOf("R"))
                .appointment("C", a -> a.duration(Duration.ofMinutes(120)).requiresAnyOf("R"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.assignments()).hasSize(2);
        assertThat(sol.assignments()).extracting(a -> a.start().toLocalDate())
                .containsExactlyInAnyOrder(MONDAY, MONDAY.plusDays(4));
        assertThat(sol.unscheduled()).hasSize(1);
    }

    @Test
    void bufferMaySpillPastLatestButReportedEndRespectsIt() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).buffer(Duration.ofMinutes(30))
                        .requiresAnyOf("R").earliest(MONDAY.atTime(9, 0)).latest(MONDAY.atTime(10, 0)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.unscheduled()).isEmpty();
        AppointmentAssignment a = sol.assignments().get(0);
        assertThat(a.start()).isEqualTo(MONDAY.atTime(9, 0));
        assertThat(a.end()).isEqualTo(MONDAY.atTime(10, 0));      // buffer not included, latest respected
        assertThat(a.duration()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void windowTooTightNearHorizonEndLeavesAppointmentUnscheduled() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").earliest(MONDAY.atTime(23, 30)))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.unscheduled()).containsExactly("A");
    }

    @Test
    void multiWindowWithPenaltyGoalPlacesTheExpensiveOne() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> r
                        .available(new TimeSlot(MONDAY.atTime(8, 0), MONDAY.atTime(9, 0)))
                        .available(new TimeSlot(MONDAY.atTime(12, 0), MONDAY.atTime(13, 0))))
                .appointment("cheap", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").penaltyIfUnscheduled(1))
                .appointment("mid", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").penaltyIfUnscheduled(5))
                .appointment("vip", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").penaltyIfUnscheduled(50))
                .goal(Goals.minimizeUnscheduledPenalty())
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.unscheduled()).containsExactly("cheap");
        assertThat(sol.objective()).hasValue(1.0);
        assertThat(sol.assignments()).extracting(a -> a.start().toLocalTime())
                .containsExactlyInAnyOrder(LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    @Test
    void weeklyTemplateWithBuffersSatisfiesOccupiedIntervalInvariant() {
        Map<String, Duration> buffers = Map.of("A", Duration.ofMinutes(15), "B", Duration.ofMinutes(15),
                "C", Duration.ZERO, "D", Duration.ofMinutes(30));
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 5)
                .resource("Chen", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(11, 0)))
                .resource("Patel", r -> r.available(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)))
                .appointment("A", a -> a.duration(Duration.ofMinutes(45)).buffer(buffers.get("A")).requiresAnyOf("Chen", "Patel"))
                .appointment("B", a -> a.duration(Duration.ofMinutes(45)).buffer(buffers.get("B")).requiresAnyOf("Chen", "Patel"))
                .appointment("C", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("Chen").latest(MONDAY.atTime(11, 0)))
                .appointment("D", a -> a.duration(Duration.ofMinutes(90)).buffer(buffers.get("D")).requiresAnyOf("Patel"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();
        assertThat(sol.unscheduled()).isEmpty();
        assertNoOccupiedOverlap(sol, buffers);
        for (AppointmentAssignment a : sol.assignments()) {
            LocalTime open = LocalTime.of(9, 0);
            LocalTime close = a.resourceId().equals("Chen") ? LocalTime.of(11, 0) : LocalTime.of(12, 0);
            assertThat(a.start().toLocalTime()).isAfterOrEqualTo(open);
            assertThat(a.end().toLocalTime()).isBeforeOrEqualTo(close);
            if (a.resourceId().equals("Patel")) assertThat(a.start().getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        }
        assertThat(sol.assignmentsOn("nobody")).isEmpty();
    }
}

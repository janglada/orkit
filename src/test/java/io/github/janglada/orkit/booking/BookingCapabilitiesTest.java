package io.github.janglada.orkit.booking;

import io.github.janglada.orkit.ModelException;
import io.github.janglada.orkit.Optimize;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Appointments needing several resources at once, and the early-start tie-break. */
class BookingCapabilitiesTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    /** No resource is double-booked, counting every resource of every appointment. */
    private static void assertNoDoubleBooking(BookingSolution sol) {
        List<AppointmentAssignment> as = sol.assignments();
        for (int i = 0; i < as.size(); i++) {
            for (int j = i + 1; j < as.size(); j++) {
                AppointmentAssignment a = as.get(i), b = as.get(j);
                boolean overlap = a.start().isBefore(b.end()) && b.start().isBefore(a.end());
                boolean shared = a.resourceIds().stream().anyMatch(b.resourceIds()::contains);
                assertThat(overlap && shared).as("%s and %s share a resource at the same time", a, b).isFalse();
            }
        }
    }

    @Test
    void anAppointmentBooksOneResourceOfEveryRequirement() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("DrA", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(13, 0)))
                .resource("DrB", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(13, 0)))
                .resource("Room1", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(11, 0)))
                .resource("Nurse", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(13, 0)))
                .appointment("Op1", a -> a.duration(Duration.ofHours(1))
                        .requiresAnyOf("DrA", "DrB").alsoRequiresAnyOf("Room1").requiresAllOf("Nurse"))
                .appointment("Op2", a -> a.duration(Duration.ofHours(1))
                        .requiresAnyOf("DrA", "DrB").alsoRequiresAnyOf("Room1").requiresAllOf("Nurse"))
                .appointment("Op3", a -> a.duration(Duration.ofHours(1))
                        .requiresAnyOf("DrA", "DrB").alsoRequiresAnyOf("Room1").requiresAllOf("Nurse"))
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        // The room is open two hours, so only two operations fit, though doctors are free.
        assertThat(sol.assignments()).hasSize(2);
        assertThat(sol.unscheduled()).hasSize(1);
        for (AppointmentAssignment a : sol.assignments()) {
            assertThat(a.resourceIds()).hasSize(3);
            assertThat(a.resourceId()).isIn("DrA", "DrB").isEqualTo(a.resourceIds().get(0));
            assertThat(a.resourceIds().subList(1, 3)).containsExactly("Room1", "Nurse");
        }
        assertThat(sol.assignmentsOn("Room1")).hasSize(2);
        assertThat(sol.assignmentsOn("Nurse")).hasSize(2);
        assertNoDoubleBooking(sol);
    }

    @Test
    void earlyStartsAreThePreferredTieBreak() {
        BookingSolution early = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R"))
                .appointment("B", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(MONDAY, LocalTime.of(10, 0))))
                .solver().timeLimit(LIMIT).solve();
        assertThat(early.isOptimal()).isTrue();
        assertThat(early.objective()).as("the goal alone is reported").hasValue(2);
        assertThat(early.gap().orElseThrow()).isZero();
        assertThat(early.assignments()).extracting(a -> a.start().toLocalTime())
                .containsExactlyInAnyOrder(LocalTime.of(9, 0), LocalTime.of(10, 0));

        BookingSolution penalties = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .appointment("A", a -> a.duration(Duration.ofMinutes(45)).requiresAnyOf("R").penaltyIfUnscheduled(3))
                .appointment("B", a -> a.duration(Duration.ofMinutes(45)).requiresAnyOf("R").penaltyIfUnscheduled(5))
                .goal(Goals.minimizeUnscheduledPenalty())
                .solver().timeLimit(LIMIT).solve();
        assertThat(penalties.objective()).hasValue(3);
        assertThat(penalties.gap().orElseThrow()).isZero();
        assertThat(penalties.assignments().get(0).start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void theTieBreakCanBeTurnedOff() {
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("R", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .appointment("A", a -> a.duration(Duration.ofMinutes(30)).requiresAnyOf("R"))
                .preferEarlyStarts(false)
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.isOptimal()).isTrue();
        assertThat(sol.objective()).hasValue(1);
    }

    @Test
    void warmStartsCarryEveryBookedResource() {
        var problem = Optimize.booking().horizon(MONDAY, 1)
                .resource("Dr", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
                .resource("Room", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
                .appointment("A", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("Dr").requiresAllOf("Room"))
                .appointment("B", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("Dr").requiresAllOf("Room"))
                .build();
        BookingSolution first = problem.solver().timeLimit(LIMIT).solve();
        BookingSolution again = problem.solver().timeLimit(LIMIT).workers(1).warmStart(first).solve();
        assertThat(again.isOptimal()).isTrue();
        assertThat(again.objective()).isEqualTo(first.objective());
        assertNoDoubleBooking(again);
    }

    @Test
    void requirementArgumentsAreValidated() {
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.duration(Duration.ofHours(1))
                .requiresAnyOf("X").alsoRequiresAnyOf("X")))
                .isInstanceOf(ModelException.class).hasMessageContaining("more than one requirement");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.alsoRequiresAnyOf()))
                .isInstanceOf(ModelException.class).hasMessageContaining("alsoRequiresAnyOf");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.requiresAllOf()))
                .isInstanceOf(ModelException.class).hasMessageContaining("requiresAllOf");
        assertThatThrownBy(() -> Optimize.booking().appointment("A", a -> a.requiresAllOf("")))
                .isInstanceOf(ModelException.class).hasMessageContaining("requiresAllOf");
        // A requirement without requiresAnyOf is enough on its own.
        BookingSolution sol = Optimize.booking().horizon(MONDAY, 1)
                .resource("Room", r -> r.availableDaily(LocalTime.of(9, 0), LocalTime.of(12, 0)))
                .appointment("A", a -> a.duration(Duration.ofHours(1)).requiresAllOf("Room"))
                .solver().timeLimit(LIMIT).solve();
        assertThat(sol.assignments().get(0).resourceId()).isEqualTo("Room");
        assertThatThrownBy(() -> Optimize.booking().horizon(MONDAY, 1)
                .resource("Room", r -> {})
                .appointment("A", a -> a.duration(Duration.ofHours(1)).requiresAllOf("Ghost"))
                .solver().solve())
                .isInstanceOf(ModelException.class).hasMessageContaining("unknown resource 'Ghost'");
    }
}

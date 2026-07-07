package opt.booking;

import opt.ModelException;
import opt.Optimize;
import opt.SolveStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final LocalDate EPOCH = LocalDate.of(2025, 1, 1);
    private static final LocalDateTime DAY = EPOCH.atTime(8, 0);       // 08:00 = minute 480
    private static final LocalDateTime DAY_END = EPOCH.atTime(16, 0);  // 16:00 = minute 960 (8h slot)

    // ── T-Bk1: single resource, single appointment fits ──────────────────────

    @Test
    void singleResourceFits() {
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("R1"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.assignments()).hasSize(1);
        assertThat(sol.unscheduled()).isEmpty();
        assertThat(sol.assignments().get(0).resourceId()).isEqualTo("R1");
    }

    // ── T-Bk2: two appointments sequential on one resource ──────────────────

    @Test
    void twoAppointmentsSequential() {
        // 8h slot, two 3h appointments: both fit, forced back-to-back by NoOverlap.
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(3)).requiresAnyOf("R1"))
                .appointment("A2", a -> a.duration(Duration.ofHours(3)).requiresAnyOf("R1"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.assignments()).hasSize(2);
        assertNoOverlap(sol.assignmentsOn("R1"));
    }

    // ── T-Bk3: slot too short → unscheduled ──────────────────────────────────

    @Test
    void slotTooShort() {
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, EPOCH.atTime(9, 0)))) // 1h slot
                .appointment("A1", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("R1"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.assignments()).isEmpty();
        assertThat(sol.unscheduled()).containsExactly("A1");
    }

    // ── T-Bk4: multi-resource selection ──────────────────────────────────────

    @Test
    void multiResourceSelection() {
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .resource("R2", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("R1", "R2"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.assignments()).hasSize(1);
        assertThat(sol.assignments().get(0).resourceId()).isIn("R1", "R2");
    }

    // ── T-Bk5: penalty scheduling — highest-penalty appointment wins ─────────

    @Test
    void penaltyScheduling() {
        // 8h slot fits at most two 5h appointments back-to-back (impossible: 5+5=10h > 8h),
        // so only one fits; the highest-penalty one must be scheduled.
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END))) // 8h
                .appointment("lowA",  a -> a.duration(Duration.ofHours(5)).requiresAnyOf("R1").penaltyIfUnscheduled(1))
                .appointment("lowB",  a -> a.duration(Duration.ofHours(5)).requiresAnyOf("R1").penaltyIfUnscheduled(1))
                .appointment("highP", a -> a.duration(Duration.ofHours(5)).requiresAnyOf("R1").penaltyIfUnscheduled(100))
                .goal(Goals.minimizeUnscheduledPenalty())
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.assignments()).hasSize(1);
        assertThat(sol.assignments().get(0).appointmentId()).isEqualTo("highP");
        assertThat(sol.unscheduled()).containsExactlyInAnyOrder("lowA", "lowB");
    }

    // ── T-Bk6: earliest/latest window respected ──────────────────────────────

    @Test
    void earliestLatestWindow() {
        LocalDateTime earliest = EPOCH.atTime(10, 0);  // minute 600
        LocalDateTime latest   = EPOCH.atTime(12, 0);  // minute 720
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> {}) // 24/7 availability
                .appointment("A1", a -> a.duration(Duration.ofHours(1))
                        .requiresAnyOf("R1")
                        .earliest(earliest)
                        .latest(latest))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.status()).isEqualTo(SolveStatus.OPTIMAL);
        assertThat(sol.assignments()).hasSize(1);
        AppointmentAssignment asg = sol.assignments().get(0);
        assertThat(asg.startMinute()).isGreaterThanOrEqualTo(600);
        assertThat(asg.endMinute()).isLessThanOrEqualTo(720);
    }

    // ── T-Bk7: structural — no overlap on same resource ──────────────────────

    @Test
    void noOverlapStructural() {
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(3)).requiresAnyOf("R1"))
                .appointment("A2", a -> a.duration(Duration.ofHours(3)).requiresAnyOf("R1"))
                .appointment("A3", a -> a.duration(Duration.ofHours(3)).requiresAnyOf("R1"))
                .solver().timeLimit(Duration.ofSeconds(10)).solve();

        assertThat(sol.isFeasible()).isTrue();
        // 3 × 3h = 9h > 8h slot → only two fit
        assertNoOverlap(sol.assignmentsOn("R1"));
    }

    // ── T-Bk8: duplicate resource id → ModelException ─────────────────────────

    @Test
    void duplicateResourceId() {
        assertThatThrownBy(() ->
                Optimize.booking()
                        .horizon(EPOCH, 1)
                        .resource("R1", r -> {})
                        .resource("R1", r -> {})
        ).isInstanceOf(ModelException.class).hasMessageContaining("R1");
    }

    // ── T-Bk9: appointment without requiresAnyOf → ModelException ─────────────

    @Test
    void appointmentNoRequires() {
        assertThatThrownBy(() ->
                Optimize.booking()
                        .horizon(EPOCH, 1)
                        .resource("R1", r -> {})
                        .appointment("A1", a -> a.duration(Duration.ofHours(1)))
        ).isInstanceOf(ModelException.class).hasMessageContaining("A1");
    }

    // ── T-Bk10: reusable build gives same results across two solves ───────────

    @Test
    void reusableBuild() {
        var problem = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("R1"))
                .appointment("A2", a -> a.duration(Duration.ofHours(2)).requiresAnyOf("R1"))
                .build();

        BookingSolution s1 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();
        BookingSolution s2 = problem.solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(s1.isFeasible()).isTrue();
        assertThat(s2.isFeasible()).isTrue();
        assertThat(s1.assignments()).hasSameSizeAs(s2.assignments());
    }

    // ── T-Bk11: runtime non-negative ─────────────────────────────────────────

    @Test
    void runtimeNonNegative() {
        BookingSolution sol = Optimize.booking()
                .horizon(EPOCH, 1)
                .resource("R1", r -> r.available(new TimeSlot(DAY, DAY_END)))
                .appointment("A1", a -> a.duration(Duration.ofHours(1)).requiresAnyOf("R1"))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();

        assertThat(sol.runtime().isNegative()).isFalse();
    }

    // ── T-Bk12: empty model — no resources → ModelException ───────────────────

    @Test
    void emptyModel() {
        assertThatThrownBy(() ->
                Optimize.booking()
                        .horizon(EPOCH, 1)
                        .solver().solve()
        ).isInstanceOf(ModelException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertNoOverlap(List<AppointmentAssignment> onResource) {
        List<AppointmentAssignment> sorted = onResource.stream()
                .sorted(java.util.Comparator.comparingLong(AppointmentAssignment::startMinute))
                .toList();
        for (int i = 0; i < sorted.size() - 1; i++) {
            AppointmentAssignment a = sorted.get(i);
            AppointmentAssignment b = sorted.get(i + 1);
            assertThat(a.endMinute())
                    .as("Overlap on resource between %s and %s", a.appointmentId(), b.appointmentId())
                    .isLessThanOrEqualTo(b.startMinute());
        }
    }

    // ── T-Bk13: horizon days boundary ───────────────────────────────────────

    @Test
    void horizonDaysLessThanOneThrowsModelException() {
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 0))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), -1))
                .isInstanceOf(ModelException.class);
    }

    // ── T-Bk14: earliest/latest without horizon is rejected ─────────────────

    @Test
    void timeBoundWithoutHorizonThrowsModelException() {
        assertThatThrownBy(() -> Optimize.booking()
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(2026, 1, 5, 9, 0)))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("horizon");
    }

    // ── T-Bk15: Multi-slot resource, malformed TimeSlot ─────────────────────

    @Test
    void multiSlotResourceThrowsModelException() {
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> r
                        .available(new TimeSlot(LocalDateTime.of(2026, 1, 5, 8, 0), LocalDateTime.of(2026, 1, 5, 9, 0)))
                        .available(new TimeSlot(LocalDateTime.of(2026, 1, 5, 10, 0), LocalDateTime.of(2026, 1, 5, 11, 0))))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("multi-slot");
    }

    @Test
    void malformedTimeSlotThrowsModelException() {
        assertThatThrownBy(() -> new TimeSlot(
                LocalDateTime.of(2026, 1, 5, 9, 0),
                LocalDateTime.of(2026, 1, 5, 9, 0))).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> new TimeSlot(
                LocalDateTime.of(2026, 1, 5, 9, 0),
                LocalDateTime.of(2026, 1, 5, 8, 0))).isInstanceOf(ModelException.class);
    }

    // ── T-Bk16: duration / penalty boundaries ───────────────────────────────

    @Test
    void durationAndPenaltyBoundaries() {
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofSeconds(59)).requiresAnyOf("R")))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 1 minute");
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").penaltyIfUnscheduled(-1)))
                .isInstanceOf(ModelException.class).hasMessageContaining(">= 0");

        // penalty 0 is allowed.
        BookingSolution sol = Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R").penaltyIfUnscheduled(0))
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
    }

    // ── T-Bk17: Duplicate appointment id ────────────────────────────────────

    @Test
    void duplicateAppointmentIdThrowsModelException() {
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R"))
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")))
                .isInstanceOf(ModelException.class).hasMessageContaining("Duplicate appointment id");
    }

    // ── T-Bk18: Time-window cross-validation ────────────────────────────────

    @Test
    void timeWindowCrossValidationThrowsModelException() {
        // earliest + duration > latest
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(2026, 1, 5, 9, 0))
                        .latest(LocalDateTime.of(2026, 1, 5, 9, 30))))
                .isInstanceOf(ModelException.class).hasMessageContaining("exceeds latest");
        // earliest before horizon start
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 6), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(2026, 1, 5, 9, 0)))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("before horizon start");
        // earliest after horizon end
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(2026, 1, 7, 9, 0)))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("after horizon end");
    }

    // ── T-Bk19: goal(null) and unknown/blank resource references ─────────────

    @Test
    void nullGoalAndBadResourceReferencesThrowModelException() {
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R"))
                .goal(null)).isInstanceOf(ModelException.class).hasMessageContaining("goal");
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("Nope"))
                .solver().solve()).isInstanceOf(ModelException.class).hasMessageContaining("unknown resource");
        assertThatThrownBy(() -> Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 1)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("", "  ")))
                .isInstanceOf(ModelException.class).hasMessageContaining("non-blank");
    }

    // ── T-Bk20: Unknown-resource accessor and multi-day horizon ─────────────

    @Test
    void unknownResourceAccessorEmptyAndMultiDayHorizon() {
        BookingSolution sol = Optimize.booking().horizon(LocalDate.of(2026, 1, 5), 2)
                .resource("R", r -> {})
                .appointment("A", a -> a.duration(Duration.ofMinutes(60)).requiresAnyOf("R")
                        .earliest(LocalDateTime.of(2026, 1, 6, 9, 0))) // day 2 of a 2-day horizon
                .solver().timeLimit(Duration.ofSeconds(5)).solve();
        assertThat(sol.isFeasible()).isTrue();
        assertThat(sol.assignmentsOn("NOPE")).isEmpty();
        // Appointment placed on day 2 => start minute >= 24*60 = 1440.
        assertThat(sol.assignments()).hasSize(1);
        assertThat(sol.assignments().get(0).startMinute()).isGreaterThanOrEqualTo(24 * 60);
    }
}

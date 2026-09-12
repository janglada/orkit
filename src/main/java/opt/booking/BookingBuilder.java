package opt.booking;

import opt.ModelException;
import opt.CpSatProblem;
import opt.CpSatSolver;
import opt.internal.AbstractCpSatSolver;
import opt.booking.internal.AppointmentDef;
import opt.booking.internal.CpSatBookingSolver;
import opt.booking.internal.ResourceDef;
import opt.booking.internal.SlotWindow;
import opt.booking.internal.WeeklyWindow;
import opt.internal.Validate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fluent builder for resource-booking / appointment-scheduling problems. Entry point: {@code Optimize.booking()}.
 * <p>
 * Declarations may appear in any order; wall-clock times are resolved against the horizon when
 * the problem is built.
 */
public final class BookingBuilder implements CpSatProblem<BookingSolution> {

    private final Map<String, ResourceTemplate> resources = new LinkedHashMap<>();
    private final Map<String, AppointmentTemplate> appointments = new LinkedHashMap<>();

    private LocalDate horizonStart = null;
    private int horizonDays = 0;
    private BookingGoal goal = Goals.maximizeScheduledAppointments();

    /** Sets the horizon: {@code days} days starting at midnight of {@code start}. All availability and time bounds must fall within it. */
    public BookingBuilder horizon(LocalDate start, int days) {
        if (start == null) throw new ModelException("horizon start must not be null.");
        if (days < 1) {
            throw new ModelException("horizon days must be >= 1, got: " + days);
        }
        this.horizonStart = start;
        this.horizonDays = days;
        return this;
    }

    /** Sets the objective. Defaults to {@link Goals#maximizeScheduledAppointments()}. */
    public BookingBuilder goal(BookingGoal g) {
        if (g == null) throw new ModelException("goal() must not be null.");
        this.goal = g;
        return this;
    }

    /** Declares a bookable resource with the given unique id, configured via the {@link ResourceConfig} lambda. */
    public BookingBuilder resource(String id, Consumer<ResourceConfig> config) {
        Validate.nonBlank(id, "resource id");
        if (resources.containsKey(id)) {
            throw new ModelException("Duplicate resource id: '" + id + "'.");
        }
        ResourceConfig cfg = new ResourceConfig();
        config.accept(cfg);
        resources.put(id, new ResourceTemplate(id, List.copyOf(cfg.slots), List.copyOf(cfg.weekly)));
        return this;
    }

    /** Declares an appointment with the given unique id, configured via the {@link AppointmentConfig} lambda. */
    public BookingBuilder appointment(String id, Consumer<AppointmentConfig> config) {
        Validate.nonBlank(id, "appointment id");
        if (appointments.containsKey(id)) {
            throw new ModelException("Duplicate appointment id: '" + id + "'.");
        }
        AppointmentConfig cfg = new AppointmentConfig();
        config.accept(cfg);
        if (cfg.duration() == null) {
            throw new ModelException("Appointment '" + id + "' must set a duration via .duration(...).");
        }
        if (cfg.resourceIds() == null || cfg.resourceIds().length == 0) {
            throw new ModelException("Appointment '" + id + "' must set candidate resources via .requiresAnyOf(...).");
        }
        long durationMinutes = cfg.duration().toMinutes();
        if (cfg.earliestDt() != null && cfg.latestDt() != null
                && cfg.earliestDt().plusMinutes(durationMinutes).isAfter(cfg.latestDt())) {
            throw new ModelException("Appointment '" + id + "': earliest " + cfg.earliestDt()
                    + " plus duration " + durationMinutes + "m exceeds latest " + cfg.latestDt() + ".");
        }
        appointments.put(id, new AppointmentTemplate(id, durationMinutes, cfg.buffer().toMinutes(),
                List.of(cfg.resourceIds()), cfg.earliestDt(), cfg.latestDt(), cfg.penalty()));
        return this;
    }

    /** Builds an immutable, reusable problem instance; resolves all wall-clock times against the horizon. */
    public CpSatProblem<BookingSolution> build() {
        if (horizonStart == null) {
            throw new ModelException("horizon(...) must be set before solving.");
        }
        LocalDateTime epoch = horizonStart.atStartOfDay();
        List<ResourceDef> resourceDefs = new ArrayList<>();
        for (ResourceTemplate t : resources.values()) resourceDefs.add(expandResource(t, epoch));
        List<AppointmentDef> appointmentDefs = new ArrayList<>();
        for (AppointmentTemplate t : appointments.values()) appointmentDefs.add(resolveAppointment(t, epoch));
        return new BookingProblem(horizonStart, horizonDays, goal, List.copyOf(resourceDefs), List.copyOf(appointmentDefs));
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public CpSatSolver<BookingSolution> solver() {
        return build().solver();
    }

    // ── Resolution against the horizon ──────────────────────────────────────

    private ResourceDef expandResource(ResourceTemplate t, LocalDateTime epoch) {
        long horizonMinutes = horizonDays * 24L * 60L;
        List<SlotWindow> windows = new ArrayList<>();
        for (TimeSlot slot : t.slots()) {
            long start = Duration.between(epoch, slot.start()).toMinutes();
            long end = Duration.between(epoch, slot.end()).toMinutes();
            if (start < 0 || end > horizonMinutes) {
                throw new ModelException("Resource '" + t.id() + "' slot " + slot.start() + "→" + slot.end()
                        + " lies outside the horizon [" + horizonStart + ", +" + horizonDays + " days).");
            }
            windows.add(new SlotWindow(start, end));
        }
        for (WeeklyWindow w : t.weekly()) {
            for (int d = 0; d < horizonDays; d++) {
                LocalDate date = horizonStart.plusDays(d);
                if (w.daily() || w.days().contains(date.getDayOfWeek())) {
                    long dayStart = d * 24L * 60L;
                    windows.add(new SlotWindow(dayStart + w.from().toSecondOfDay() / 60,
                            dayStart + w.to().toSecondOfDay() / 60));
                }
            }
        }
        return new ResourceDef(t.id(), mergeWindows(windows));
    }

    /** Sorts windows and merges overlapping or touching ones into maximal availability windows. */
    static List<SlotWindow> mergeWindows(List<SlotWindow> windows) {
        List<SlotWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingLong(SlotWindow::startMin));
        List<SlotWindow> merged = new ArrayList<>();
        for (SlotWindow w : sorted) {
            if (!merged.isEmpty() && w.startMin() <= merged.get(merged.size() - 1).endMin()) {
                SlotWindow last = merged.remove(merged.size() - 1);
                merged.add(new SlotWindow(last.startMin(), Math.max(last.endMin(), w.endMin())));
            } else {
                merged.add(w);
            }
        }
        return List.copyOf(merged);
    }

    private AppointmentDef resolveAppointment(AppointmentTemplate t, LocalDateTime epoch) {
        Long earliest = t.earliest() == null ? null : minutesFromEpoch(epoch, t.earliest(), "earliest", t.id());
        Long latest = t.latest() == null ? null : minutesFromEpoch(epoch, t.latest(), "latest", t.id());
        return new AppointmentDef(t.id(), t.durationMinutes(), t.bufferMinutes(), t.resourceIds(), earliest, latest, t.penalty());
    }

    private long minutesFromEpoch(LocalDateTime epoch, LocalDateTime dt, String label, String apptId) {
        if (dt.isBefore(epoch)) {
            throw new ModelException("Appointment '" + apptId + "' " + label + " " + dt
                    + " is before horizon start " + epoch.toLocalDate() + ".");
        }
        long horizonMinutes = horizonDays * 24L * 60L;
        long offset = Duration.between(epoch, dt).toMinutes();
        if (offset > horizonMinutes) {
            throw new ModelException("Appointment '" + apptId + "' " + label + " " + dt
                    + " is after horizon end.");
        }
        return offset;
    }

    /** A declared resource before its availability is resolved against the horizon. */
    private record ResourceTemplate(String id, List<TimeSlot> slots, List<WeeklyWindow> weekly) {}

    /** A declared appointment before its wall-clock bounds are resolved against the horizon. */
    private record AppointmentTemplate(String id, long durationMinutes, long bufferMinutes, List<String> resourceIds,
                                       LocalDateTime earliest, LocalDateTime latest, long penalty) {}

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class BookingProblem implements CpSatProblem<BookingSolution> {

        private final LocalDate horizonStart;
        private final int horizonDays;
        private final BookingGoal goal;
        private final List<ResourceDef> resources;
        private final List<AppointmentDef> appointments;

        BookingProblem(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                       List<ResourceDef> resources, List<AppointmentDef> appointments) {
            this.horizonStart = horizonStart;
            this.horizonDays = horizonDays;
            this.goal = goal;
            this.resources = resources;
            this.appointments = appointments;
        }

        @Override
        public CpSatSolver<BookingSolution> solver() {
            return new BookingSolver(horizonStart, horizonDays, goal, resources, appointments);
        }
    }

    private static final class BookingSolver extends AbstractCpSatSolver<BookingSolution> {

        private final LocalDate horizonStart;
        private final int horizonDays;
        private final BookingGoal goal;
        private final List<ResourceDef> resources;
        private final List<AppointmentDef> appointments;

        BookingSolver(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                      List<ResourceDef> resources, List<AppointmentDef> appointments) {
            this.horizonStart = horizonStart;
            this.horizonDays = horizonDays;
            this.goal = goal;
            this.resources = resources;
            this.appointments = appointments;
        }

        @Override
        public BookingSolution solve() {
            return new CpSatBookingSolver(horizonStart, horizonDays, goal, resources, appointments, params, listener, warmStart).solve();
        }
    }
}

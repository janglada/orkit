package opt.booking;

import opt.ModelException;
import opt.OptimizationProblem;
import opt.Solver;
import opt.booking.internal.AppointmentDef;
import opt.booking.internal.CpSatBookingSolver;
import opt.booking.internal.ResourceDef;
import opt.internal.Validate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent builder for resource-booking / appointment-scheduling problems. Entry point: {@code Optimize.booking()}. */
public final class BookingBuilder implements OptimizationProblem<BookingSolution> {

    private final List<ResourceDef> resources = new ArrayList<>();
    private final List<AppointmentDef> appointments = new ArrayList<>();
    private final Set<String> resourceIds = new HashSet<>();
    private final Set<String> appointmentIds = new HashSet<>();

    private LocalDate horizonStart = null;
    private int horizonDays = 0;
    private BookingGoal goal = Goals.maximizeScheduledAppointments();

    /** Sets the epoch (inclusive) of the solver horizon. All availability slots and earliest/latest bounds must fall within it. */
    public BookingBuilder horizon(LocalDate start, int days) {
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
        if (!resourceIds.add(id)) {
            throw new ModelException("Duplicate resource id: '" + id + "'.");
        }
        ResourceConfig cfg = new ResourceConfig();
        config.accept(cfg);
        if (cfg.slots.size() > 1) {
            throw new ModelException("Resource '" + id + "' declares " + cfg.slots.size()
                    + " slots; multi-slot resources are not supported yet.");
        }
        resources.add(new ResourceDef(id, cfg.slots()));
        return this;
    }

    /** Declares an appointment with the given unique id, configured via the {@link AppointmentConfig} lambda. */
    public BookingBuilder appointment(String id, Consumer<AppointmentConfig> config) {
        Validate.nonBlank(id, "appointment id");
        if (!appointmentIds.add(id)) {
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

        // Convert earliest/latest LocalDateTime to minute offsets from the epoch; validated against the horizon.
        Long earliestOffset = null;
        Long latestOffset = null;
        if (cfg.earliestDt() != null) {
            earliestOffset = minutesFromEpoch(cfg.earliestDt(), "earliest", id);
        }
        if (cfg.latestDt() != null) {
            latestOffset = minutesFromEpoch(cfg.latestDt(), "latest", id);
        }
        if (earliestOffset != null && latestOffset != null && earliestOffset + durationMinutes > latestOffset) {
            throw new ModelException("Appointment '" + id + "': earliest " + cfg.earliestDt()
                    + " plus duration " + durationMinutes + "m exceeds latest " + cfg.latestDt() + ".");
        }
        appointments.add(new AppointmentDef(id, durationMinutes, List.of(cfg.resourceIds()),
                earliestOffset, latestOffset, cfg.penalty()));
        return this;
    }

    private long minutesFromEpoch(LocalDateTime dt, String label, String apptId) {
        LocalDateTime epoch = epochStart();
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

    private LocalDateTime epochStart() {
        if (horizonStart == null) {
            throw new ModelException("horizon(...) must be set before declaring appointments/resources with time bounds.");
        }
        return horizonStart.atStartOfDay();
    }

    /** Builds an immutable, reusable problem instance. */
    public OptimizationProblem<BookingSolution> build() {
        return new BookingProblem(horizonStart, horizonDays, goal,
                List.copyOf(resources), List.copyOf(appointments));
    }

    /** Convenience: calls {@code build().solver()} for the single-solve case. */
    @Override
    public Solver<BookingSolution> solver() {
        return build().solver();
    }

    // ── Problem + Solver ────────────────────────────────────────────────────

    private static final class BookingProblem implements OptimizationProblem<BookingSolution> {

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
        public Solver<BookingSolution> solver() {
            return new BookingSolver(horizonStart, horizonDays, goal, resources, appointments);
        }
    }

    private static final class BookingSolver implements Solver<BookingSolution> {

        private final LocalDate horizonStart;
        private final int horizonDays;
        private final BookingGoal goal;
        private final List<ResourceDef> resources;
        private final List<AppointmentDef> appointments;

        private Duration timeLimit = Duration.ofMinutes(1);
        private int workers = 1;
        private long seed = 0;
        private boolean verbose = false;

        BookingSolver(LocalDate horizonStart, int horizonDays, BookingGoal goal,
                      List<ResourceDef> resources, List<AppointmentDef> appointments) {
            this.horizonStart = horizonStart;
            this.horizonDays = horizonDays;
            this.goal = goal;
            this.resources = resources;
            this.appointments = appointments;
        }

        @Override public Solver<BookingSolution> timeLimit(Duration limit) { timeLimit = limit; return this; }
        @Override public Solver<BookingSolution> workers(int threads)       { workers = threads;  return this; }
        @Override public Solver<BookingSolution> seed(long randomSeed)      { seed = randomSeed;  return this; }
        @Override public Solver<BookingSolution> verbose(boolean log)       { verbose = log;      return this; }

        @Override
        public BookingSolution solve() {
            return new CpSatBookingSolver(horizonStart, horizonDays, goal, resources, appointments)
                    .timeLimit(timeLimit)
                    .workers(workers)
                    .seed(seed)
                    .verbose(verbose)
                    .solve();
        }
    }
}

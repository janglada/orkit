package io.github.janglada.orkit.packing;

import io.github.janglada.orkit.Solution;
import io.github.janglada.orkit.SolveDiagnostics;
import io.github.janglada.orkit.SolveStatus;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** The solved result of a container-loading / 3D bin packing problem. */
public final class PackingSolution implements Solution {

    private final SolveStatus status;
    private final SolveDiagnostics diagnostics;
    private final OptionalDouble objective;
    private final Duration runtime;
    private final List<ContainerAssignment> assignments;
    private final List<String> unassignedBoxes;

    /** Creates a result without search statistics ({@link SolveDiagnostics#none()}). */
    public PackingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<ContainerAssignment> assignments,
                           List<String> unassignedBoxes) {
        this(status, objective, runtime, assignments, unassignedBoxes, SolveDiagnostics.none());
    }

    /** Creates a result with the given search statistics. */
    public PackingSolution(SolveStatus status, OptionalDouble objective, Duration runtime,
                           List<ContainerAssignment> assignments,
                           List<String> unassignedBoxes,
                           SolveDiagnostics diagnostics) {
        this.status = status;
        this.diagnostics = (diagnostics == null) ? SolveDiagnostics.none() : diagnostics;
        this.objective = objective;
        this.runtime = runtime;
        this.assignments = Collections.unmodifiableList(assignments);
        this.unassignedBoxes = Collections.unmodifiableList(unassignedBoxes);
    }

    @Override public SolveStatus status()       { return status; }
    @Override public OptionalDouble objective() { return objective; }
    @Override public Duration runtime()         { return runtime; }
    /** Search statistics reported by CP-SAT for this result. */
    @Override public SolveDiagnostics diagnostics() { return diagnostics; }

    /** One {@link ContainerAssignment} per declared container, in declaration order (empty containers included). */
    public List<ContainerAssignment> assignments() {
        return assignments;
    }

    /** Box identifiers that were not placed into any container. */
    public List<String> unassignedBoxes() {
        return unassignedBoxes;
    }

    /** The assignment for the given container id; empty for an unknown container or when no solution was found. */
    public Optional<ContainerAssignment> assignmentOf(String containerId) {
        return assignments.stream()
                .filter(a -> a.containerId().equals(containerId))
                .findFirst();
    }

    /** The container id holding the given box; empty when the box is unassigned or unknown. */
    public Optional<String> containerOf(String boxId) {
        for (ContainerAssignment a : assignments) {
            for (ContainerAssignment.BoxPlacement p : a.boxes()) {
                if (p.boxId().equals(boxId)) return Optional.of(a.containerId());
            }
        }
        return Optional.empty();
    }

    /** The number of containers that received at least one box. */
    public long containersUsed() {
        return assignments.stream().filter(a -> !a.boxes().isEmpty()).count();
    }
}

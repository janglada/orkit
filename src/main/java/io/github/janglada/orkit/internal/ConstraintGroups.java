package io.github.janglada.orkit.internal;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.Constraint;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.Literal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Labels groups of constraints so an infeasible model can say which groups conflict. When
 * active, every guarded constraint is enforced only under its group's literal, and the solve
 * assumes every such literal; CP-SAT then reports a subset of assumptions that cannot hold
 * together. When inactive, guarding does nothing, so domain code has a single path.
 */
public final class ConstraintGroups {

    private static final ConstraintGroups OFF = new ConstraintGroups(null);

    private final CpModel model;
    private final Map<String, BoolVar> literals = new LinkedHashMap<>();
    private final Map<Integer, String> labelByIndex = new HashMap<>();

    private ConstraintGroups(CpModel model) {
        this.model = model;
    }

    /** Groups that guard nothing. */
    public static ConstraintGroups off() {
        return OFF;
    }

    /** Active groups on {@code model}. */
    static ConstraintGroups on(CpModel model) {
        return new ConstraintGroups(model);
    }

    /** Whether constraints are being labeled. */
    public boolean active() {
        return model != null;
    }

    /** Whether these groups belong to {@code m}. */
    boolean isFor(CpModel m) {
        return model == m;
    }

    /** Enforces {@code constraint} only under the literal of {@code label}; a no-op when inactive. */
    public void guard(Constraint constraint, String label) {
        if (model == null) return;
        BoolVar lit = literals.computeIfAbsent(label, l -> {
            BoolVar v = model.newBoolVar("group_" + literals.size());
            labelByIndex.put(v.getIndex(), l);
            return v;
        });
        constraint.onlyEnforceIf(lit);
    }

    /** Assumes every group literal true for the coming solve. */
    void assumeAll() {
        if (literals.isEmpty()) return;
        model.addAssumptions(literals.values().toArray(new Literal[0]));
    }

    /** Labels of the given literal indices, in the order the groups were declared. */
    List<String> labels(List<Integer> indices) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BoolVar> e : literals.entrySet()) {
            if (indices.contains(e.getValue().getIndex())) out.add(e.getKey());
        }
        return out;
    }
}

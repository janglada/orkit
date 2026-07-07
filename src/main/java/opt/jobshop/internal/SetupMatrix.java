package opt.jobshop.internal;

import java.util.Map;

/**
 * Immutable setup-time matrix for sequence-dependent changeovers.
 *
 * <p>Resolution order for a transition from {@code fromType} to {@code toType}
 * on machine {@code M} (either type {@code null} means "untyped"):
 * <ol>
 *   <li>untyped side &#8594; {@code 0} (defaults are never applied to untyped ops),</li>
 *   <li>same typed pair &#8594; machine-specific {@code (T,T)} &#8594; global {@code (T,T)} &#8594; {@code 0},</li>
 *   <li>different typed pair &#8594; machine-specific &#8594; global &#8594; {@code defaultSetupTime} &#8594; {@code 0}.</li>
 * </ol>
 */
public record SetupMatrix(
        /** Global {@code (from, to) -> setup} entries, applied on every machine. */
        Map<String, Map<String, Integer>> global,
        /** Per-machine {@code machine -> (from, to) -> setup} overrides. */
        Map<String, Map<String, Map<String, Integer>>> perMachine,
        /** Setup applied to any unlisted different-type transition; {@code 0} if unset. */
        int defaultSetupTime,
        /** {@code machine -> initial type} the machine held before the horizon. */
        Map<String, String> initialTypes
) {
    private static final SetupMatrix EMPTY = new SetupMatrix(
            Map.of(), Map.of(), 0, Map.of());

    /** Returns the empty matrix (no setups configured anywhere). */
    public static SetupMatrix empty() {
        return EMPTY;
    }

    /** {@code true} when no setup configuration of any kind is present. */
    public boolean isEmpty() {
        return global.isEmpty() && perMachine.isEmpty()
                && defaultSetupTime == 0 && initialTypes.isEmpty();
    }

    /**
     * Resolves the setup time between two operations on the given machine.
     *
     * @param machine  the machine the transition occurs on
     * @param fromType previous operation's type, or {@code null} if untyped / initial
     * @param toType   next operation's type, or {@code null} if untyped
     * @return the non-negative setup time
     */
    public int resolve(String machine, String fromType, String toType) {
        if (fromType == null || toType == null) {
            return 0;
        }
        if (fromType.equals(toType)) {
            // Same type: only an explicit (T, T) entry applies; no default.
            int ms = machineSpecific(machine, fromType, toType);
            if (machineSpecificPresent(machine, fromType, toType)) return ms;
            if (globalPresent(fromType, toType)) {
                return global.get(fromType).get(toType);
            }
            return 0;
        }
        // Different typed: machine-specific -> global -> default -> 0.
        if (machineSpecificPresent(machine, fromType, toType)) {
            return machineSpecific(machine, fromType, toType);
        }
        if (globalPresent(fromType, toType)) {
            return global.get(fromType).get(toType);
        }
        return defaultSetupTime;
    }

    private boolean machineSpecificPresent(String machine, String fromType, String toType) {
        Map<String, Map<String, Integer>> m = perMachine.get(machine);
        return m != null && m.containsKey(fromType) && m.get(fromType).containsKey(toType);
    }

    private int machineSpecific(String machine, String fromType, String toType) {
        Map<String, Map<String, Integer>> m = perMachine.get(machine);
        if (m == null) return 0;
        return m.getOrDefault(fromType, Map.of()).getOrDefault(toType, 0);
    }

    private boolean globalPresent(String fromType, String toType) {
        return global.containsKey(fromType) && global.get(fromType).containsKey(toType);
    }

    /** The initial type a machine held before the horizon, or {@code null} if unset. */
    public String initialType(String machine) {
        return initialTypes.get(machine);
    }

    /** The largest setup value reachable by {@link #resolve}, or {@code 0} if none configured. */
    public int maxSetupValue() {
        int max = defaultSetupTime;
        for (Map<String, Integer> row : global.values()) {
            for (int v : row.values()) max = Math.max(max, v);
        }
        for (Map<String, Map<String, Integer>> perMachine : perMachine.values()) {
            for (Map<String, Integer> row : perMachine.values()) {
                for (int v : row.values()) max = Math.max(max, v);
            }
        }
        return max;
    }

    /** Every type string appearing as a {@code from} or {@code to} key in the matrix. */
    public java.util.Set<String> referencedTypes() {
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (var entry : global.entrySet()) {
            types.add(entry.getKey());
            types.addAll(entry.getValue().keySet());
        }
        for (var perMachine : perMachine.values()) {
            for (var entry : perMachine.entrySet()) {
                types.add(entry.getKey());
                types.addAll(entry.getValue().keySet());
            }
        }
        return types;
    }
}


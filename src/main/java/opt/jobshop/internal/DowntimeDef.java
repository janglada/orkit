package opt.jobshop.internal;

/**
 * A window {@code [from, to)} during which a machine is unavailable. Operations are
 * non-preemptive, so none may overlap it.
 *
 * @param machine the machine id
 * @param from    inclusive start, in model time units
 * @param to      exclusive end, in model time units; strictly after {@code from}
 */
public record DowntimeDef(String machine, long from, long to) {}

package io.github.janglada.orkit.jobshop.internal;

/**
 * A precedence between operations of two jobs: the {@code before} operation ends before the
 * {@code after} operation starts.
 *
 * @param beforeJob job of the earlier operation
 * @param beforeOp  index of the earlier operation within its job
 * @param afterJob  job of the later operation
 * @param afterOp   index of the later operation within its job
 */
public record PrecedenceDef(String beforeJob, int beforeOp, String afterJob, int afterOp) {}

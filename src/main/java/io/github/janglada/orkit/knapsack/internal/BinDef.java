package io.github.janglada.orkit.knapsack.internal;

/**
 * Immutable description of a knapsack (bin).
 *
 * @param id       the bin identifier
 * @param capacity the maximum total weight this bin can hold
 */
public record BinDef(String id, long capacity) {}

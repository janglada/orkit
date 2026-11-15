package io.github.janglada.orkit.knapsack.internal;

/**
 * Immutable description of an item that may be placed in a knapsack.
 *
 * @param id     the item identifier
 * @param profit the profit earned by packing this item
 * @param weight the weight this item contributes toward its bin's capacity
 */
public record ItemDef(String id, long profit, long weight) {}

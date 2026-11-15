package io.github.janglada.orkit.knapsack;

import java.util.List;

/**
 * The items assigned to a single knapsack (bin) in a solved solution.
 *
 * @param bin           the bin identifier
 * @param capacity      the bin's declared weight capacity
 * @param items         the item identifiers packed into this bin (insertion order)
 * @param usedCapacity  the sum of the weights of {@code items}
 * @param profit        the sum of the profits of {@code items}
 */
public record BinAssignment(String bin, long capacity, List<String> items, long usedCapacity, long profit) {}

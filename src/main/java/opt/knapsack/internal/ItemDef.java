package opt.knapsack.internal;

/** Immutable description of an item that may be placed in a knapsack. */
public record ItemDef(String id, long profit, long weight) {}

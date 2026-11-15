package io.github.janglada.orkit.routing;

/** Ordering policy among pickup-and-delivery pairs carried by the same vehicle. */
public enum PickupDeliveryPolicy {
    /** No ordering constraint between different pairs (each delivery still follows its pickup). */
    ANY,
    /** First picked up, first delivered: pairs may not nest. */
    FIFO,
    /** Last picked up, first delivered: pairs must nest, as with a stack-loaded truck. */
    LIFO
}

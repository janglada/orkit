package io.github.janglada.orkit.routing.internal;

/**
 * A pickup-and-delivery pair: {@code pickupId} must be visited before {@code deliveryId}
 * by the same vehicle, and the two are dropped together when dropping is allowed.
 *
 * @param pickupId   id of the pickup stop
 * @param deliveryId id of the delivery stop
 */
public record PickupDeliveryDef(String pickupId, String deliveryId) {}

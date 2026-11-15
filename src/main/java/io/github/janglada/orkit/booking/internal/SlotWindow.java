package io.github.janglada.orkit.booking.internal;

/**
 * One availability window of a resource as minute offsets from the solver epoch,
 * half-open {@code [startMin, endMin)}.
 */
public record SlotWindow(long startMin, long endMin) {}

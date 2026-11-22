package io.github.janglada.orkit.scheduling.internal;

/**
 * A staffing role on a shift: {@code count} of the assigned employees must hold {@code skill}
 * and fill this role.
 *
 * @param skill the skill the role requires
 * @param count how many employees fill the role
 */
public record RoleDef(String skill, int count) {}

package edu.arena.core.model;

import java.util.Objects;

/**
 * Tank snapshot: stable slot 0..63, world units, velocity units/second, angles in radians, timers
 * in ticks. Shield ticks count protected future transitions.
 */
public record Tank(
        int slot,
        Vec2 position,
        Vec2 velocity,
        double heading,
        double gunHeading,
        int health,
        int cooldownTicks,
        int shieldTicks,
        int shieldCooldownTicks) {
    public Tank {
        if (slot < 0
                || slot > 63
                || health < 0
                || cooldownTicks < 0
                || shieldTicks < 0
                || shieldCooldownTicks < 0
                || !Double.isFinite(heading)
                || !Double.isFinite(gunHeading)) {
            throw new IllegalArgumentException("Invalid tank state");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
    }

    public Tank(
            int slot,
            Vec2 position,
            Vec2 velocity,
            double heading,
            double gunHeading,
            int health,
            int cooldownTicks) {
        this(slot, position, velocity, heading, gunHeading, health, cooldownTicks, 0, 0);
    }
}

package edu.arena.core.model;

import java.util.Objects;

/** A projectile's stable id, owner slot, position in world units and velocity in units/second. */
public record Projectile(long id, int owner, Vec2 position, Vec2 velocity) {
    public Projectile {
        if (id < 0 || owner < 0 || owner > 63) {
            throw new IllegalArgumentException("Invalid projectile identity");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
    }
}

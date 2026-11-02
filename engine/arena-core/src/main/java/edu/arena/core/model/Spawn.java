package edu.arena.core.model;

import java.util.Objects;

/** Starting position in world units and hull/gun heading in radians. */
public record Spawn(Vec2 position, double heading) {
    public Spawn {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(heading)) {
            throw new IllegalArgumentException("Heading must be finite");
        }
    }
}

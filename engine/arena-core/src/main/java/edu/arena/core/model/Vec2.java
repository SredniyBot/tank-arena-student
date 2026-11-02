package edu.arena.core.model;

import java.util.Objects;

/** Immutable 2D vector. Positions use world units; velocities use world units/second. */
public record Vec2(double x, double y) {
    public Vec2 {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Vector must be finite");
        }
    }

    public Vec2 add(Vec2 other) {
        Objects.requireNonNull(other);
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 subtract(Vec2 other) {
        Objects.requireNonNull(other);
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 scale(double factor) {
        return new Vec2(x * factor, y * factor);
    }

    public double length() {
        return Math.hypot(x, y);
    }

    public double distance(Vec2 other) {
        return Math.hypot(x - other.x, y - other.y);
    }
}

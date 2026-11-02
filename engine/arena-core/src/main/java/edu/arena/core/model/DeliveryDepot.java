package edu.arena.core.model;

import java.util.Objects;

/** Neutral pickup/delivery location; cargo names the depot which accepts it. */
public record DeliveryDepot(int id, Vec2 position, double radius) {
    public DeliveryDepot {
        if (id < 0 || !Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Invalid delivery depot");
        }
        Objects.requireNonNull(position, "position");
    }
}

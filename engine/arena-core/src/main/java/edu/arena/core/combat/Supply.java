package edu.arena.core.combat;

import edu.arena.core.model.Vec2;
import java.util.Objects;

/** A shared, destructible field stock. Units are consumed in place, never transported. */
public record Supply(
        int id, Kind kind, Vec2 position, int stock, int capacity, int health, int nextRefillTick) {
    public enum Kind {
        AMMUNITION,
        REPAIR,
        TURRET_MODULE
    }

    public Supply {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        if (id < 0
                || capacity < 1
                || stock < 0
                || stock > capacity
                || health < 0
                || health > 3
                || nextRefillTick < 0) {
            throw new IllegalArgumentException("Invalid field stock");
        }
    }

    public Supply damaged(int tick, int tickRate) {
        int remaining = Math.max(0, health - 1);
        return new Supply(
                id,
                kind,
                position,
                remaining == 0 ? 0 : stock,
                capacity,
                remaining,
                remaining == 0 ? tick + 20 * tickRate : nextRefillTick);
    }

    public Supply consume() {
        return new Supply(id, kind, position, stock - 1, capacity, health, nextRefillTick);
    }
}

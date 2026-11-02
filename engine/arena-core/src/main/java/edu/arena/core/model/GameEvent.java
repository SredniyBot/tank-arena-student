package edu.arena.core.model;

import java.util.Objects;

/**
 * A tick event; actor/target are slots 0..63, or -1 when no single participant applies. Position
 * uses world units.
 */
public record GameEvent(EventType type, int actor, int target, Vec2 position) {
    public GameEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        if (actor < -1 || actor > 63 || target < -1 || target > 63) {
            throw new IllegalArgumentException("Invalid event slots");
        }
    }
}

package edu.arena.core.model;

import java.util.Objects;

/** One reusable crate; carrier -1 means ground or waiting at its source until availableAtTick. */
public record Cargo(
        int id,
        int sourceId,
        int destinationId,
        Vec2 position,
        int carrierSlot,
        int availableAtTick) {
    public Cargo {
        if (id < 0
                || sourceId < 0
                || destinationId < 0
                || sourceId == destinationId
                || carrierSlot < -1
                || carrierSlot > 63
                || availableAtTick < 0) {
            throw new IllegalArgumentException("Invalid cargo");
        }
        Objects.requireNonNull(position, "position");
    }
}

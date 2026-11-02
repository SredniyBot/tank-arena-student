package edu.arena.core.model;

import java.util.Objects;

/** A single objective transition, owned by exactly one completed simulation tick. */
public record DeliveryEvent(int tick, int slot, int cargoId, Kind kind) {
    public enum Kind {
        PICKUP,
        DROPPED,
        DELIVERED
    }

    public DeliveryEvent {
        if (tick < 0 || slot < 0 || slot > 63 || cargoId < 0) {
            throw new IllegalArgumentException("Invalid delivery event");
        }
        Objects.requireNonNull(kind, "kind");
    }
}

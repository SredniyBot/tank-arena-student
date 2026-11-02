package edu.arena.core.model;

import java.util.Set;

/** One current-life elimination per dead slot. INACTIVE reserves an empty live-arena slot. */
public record Elimination(int slot, int tick, String reason, int actorSlot) {
    private static final Set<String> REASONS =
            Set.of(
                    "DESTROYED",
                    "ZONE",
                    "FORFEIT",
                    "COMMAND_TIMEOUT",
                    "REVOKED",
                    "ADMIN_KICK",
                    "INACTIVE",
                    "LEFT");

    public Elimination {
        if (slot < 0
                || slot > 63
                || tick < 0
                || reason == null
                || !REASONS.contains(reason)
                || actorSlot < -1
                || actorSlot > 63) {
            throw new IllegalArgumentException("Invalid elimination");
        }
    }

    public Elimination(int slot, int tick, String reason) {
        this(slot, tick, reason, -1);
    }
}

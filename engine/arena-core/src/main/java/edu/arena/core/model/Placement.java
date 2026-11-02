package edu.arena.core.model;

/** Survival score: earlier eliminations earn points; simultaneous eliminations share rank. */
public record Placement(int slot, int rank, int points, int eliminatedTick) {
    public Placement {
        if (slot < 0
                || slot > 63
                || rank < 1
                || rank > 64
                || points < 0
                || points > 63
                || eliminatedTick < -1) {
            throw new IllegalArgumentException("Invalid placement");
        }
    }
}

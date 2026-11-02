package edu.arena.core.recon;

import edu.arena.core.model.GameState;
import java.util.List;
import java.util.Objects;

/** Authoritative world and lifecycle. This full state belongs only to the host and teacher. */
public record ReconState(
        GameState arena,
        List<Integer> teamScores,
        List<Integer> respawnAt,
        List<Long> lives,
        List<Integer> controlTicks) {
    /** Backward-compatible Java construction for delivery mode. */
    public ReconState(
            GameState arena, List<Integer> teamScores, List<Integer> respawnAt, List<Long> lives) {
        this(
                arena,
                teamScores,
                respawnAt,
                lives,
                java.util.Collections.nCopies(teamScores.size(), 0));
    }

    public ReconState withArena(GameState world) {
        return new ReconState(world, teamScores, respawnAt, lives, controlTicks);
    }

    public ReconState {
        Objects.requireNonNull(arena, "arena");
        teamScores = List.copyOf(teamScores);
        respawnAt = List.copyOf(respawnAt);
        lives = List.copyOf(lives);
        controlTicks = List.copyOf(controlTicks);
        int slots = arena.tanks().size();
        if (teamScores.size() < 2
                || teamScores.size() > 64
                || slots < teamScores.size()
                || controlTicks.size() != teamScores.size()
                || controlTicks.stream().anyMatch(tick -> tick < 0)
                || respawnAt.size() != slots
                || lives.size() != slots
                || teamScores.stream().anyMatch(score -> score < 0)
                || respawnAt.stream().anyMatch(tick -> tick < 0)
                || lives.stream().anyMatch(life -> life < 0)) {
            throw new IllegalArgumentException("Invalid recon state dimensions or counters");
        }
    }
}

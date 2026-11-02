package edu.arena.core.bot;

import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Tank;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A bot sees the same completed snapshot as every opponent, without their pending commands. */
public record Observation(
        int self, GameState state, ArenaMap map, GameRules rules, ControlTiming timing) {
    public Observation(int self, GameState state, ArenaMap map, GameRules rules) {
        this(self, state, map, rules, ControlTiming.immediate(state.tick()));
    }

    public Observation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(timing, "timing");
        if (timing.observedTick() != state.tick()) {
            throw new IllegalArgumentException("Timing must describe the observed state");
        }
        if (self < 0 || self >= state.tanks().size()) {
            throw new IllegalArgumentException("Self must be a participant slot");
        }
    }

    public Tank me() {
        return state.tanks().get(self);
    }

    public List<Tank> opponents() {
        return state.tanks().stream()
                .filter(tank -> tank.slot() != self && tank.health() > 0)
                .toList();
    }

    public Optional<Tank> nearestOpponent() {
        return opponents().stream()
                .min(
                        Comparator.comparingDouble(
                                        (Tank tank) -> me().position().distance(tank.position()))
                                .thenComparingDouble(tank -> tank.position().x())
                                .thenComparingDouble(tank -> tank.position().y()));
    }
}

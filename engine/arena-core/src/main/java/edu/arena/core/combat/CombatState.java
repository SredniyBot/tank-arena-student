package edu.arena.core.combat;

import java.util.ArrayList;
import java.util.List;

/** Full authoritative equipment and stocks. Never send this record to a player unfiltered. */
public record CombatState(List<Equipment> equipment, List<Supply> supplies, List<Long> scores) {
    public static final int START_AMMUNITION = 12;
    public static final int MAX_AMMUNITION = 24;
    public static final double INTERACTION_RADIUS = 48;
    public static final double SUPPLY_RADIUS = 12;
    public static final double UPGRADED_TURRET_FACTOR = 1.35;
    public static final int POINTS_PER_DEATH = 100;

    public CombatState {
        equipment = List.copyOf(equipment);
        supplies = List.copyOf(supplies);
        scores = List.copyOf(scores);
        if (equipment.size() < 2 || equipment.size() > 64 || scores.size() != equipment.size()) {
            throw new IllegalArgumentException("Invalid independent arena roster");
        }
    }

    public CombatState newLife(int slot) {
        var next = new ArrayList<>(equipment);
        next.set(slot, Equipment.fresh());
        return new CombatState(next, supplies, scores);
    }

    public CombatState withScores(List<Long> values) {
        return new CombatState(equipment, supplies, values);
    }
}

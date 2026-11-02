package edu.arena.core.combat;

import java.util.List;

/** Own equipment, currently observed stocks, and public scores; no remote equipment is exposed. */
public record CombatView(
        Equipment equipment,
        List<Supply> visibleSupplies,
        List<Long> scores,
        int maxAmmunition,
        double interactionRadius,
        int repairDurationTicks,
        double upgradedTurretFactor) {
    public CombatView {
        visibleSupplies = List.copyOf(visibleSupplies);
        scores = List.copyOf(scores);
    }
}

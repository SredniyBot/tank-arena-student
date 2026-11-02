package edu.arena.core.recon;

import edu.arena.core.model.EventType;
import edu.arena.core.model.GameState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One team point for a death caused by that team, including simultaneous allied hits. */
final class TeamCombatScoring {
    private TeamCombatScoring() {}

    static List<Integer> score(
            GameState before, GameState after, ReconRules mode, List<Integer> previous) {
        var result = new ArrayList<>(previous);
        for (var victim : after.tanks()) {
            if (victim.health() > 0 || before.tanks().get(victim.slot()).health() == 0) {
                continue;
            }
            var attackers = new HashSet<Integer>();
            for (var event : after.events()) {
                if (event.type() == EventType.HIT
                        && event.target() == victim.slot()
                        && event.actor() >= 0) {
                    int team = mode.teamOf(event.actor());
                    if (team != mode.teamOf(victim.slot())) {
                        attackers.add(team);
                    }
                }
            }
            // Hits from different teams at the same instant have no privileged processing order.
            if (attackers.size() == 1) {
                int team = attackers.iterator().next();
                result.set(team, Math.addExact(result.get(team), 1));
            }
        }
        return List.copyOf(result);
    }
}

package edu.arena.core.recon;

import edu.arena.core.bot.ControlTiming;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Cargo;
import edu.arena.core.model.DeliveryDepot;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.Projectile;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A local snapshot. Missing objects are unobserved, not known to be dead or absent. Static
 * geometry, roster teams and team scores are public. No world events or remote life counters are
 * included. A dead observer receives only its own tank and public data.
 *
 * <p>Visible tanks exclude self. Cargo is included only when currently available and visible;
 * hidden cargo carriers never reveal their position through a cargo record. Free cargo's
 * destinationId has no routing meaning: collection assigns the carrier's team base.
 */
public record ReconObservation(
        int self,
        int team,
        long life,
        Tank me,
        List<Tank> visibleTanks,
        List<Projectile> projectiles,
        List<Cargo> cargo,
        List<DeliveryDepot> depots,
        List<Vec2> sources,
        List<Integer> teamBySlot,
        List<Integer> teamScores,
        ArenaMap map,
        GameRules rules,
        ReconRules mode,
        ControlTiming timing,
        String roundId,
        edu.arena.core.combat.CombatView combat) {
    public ReconObservation(
            int self,
            int team,
            long life,
            Tank me,
            List<Tank> visibleTanks,
            List<Projectile> projectiles,
            List<Cargo> cargo,
            List<DeliveryDepot> depots,
            List<Vec2> sources,
            List<Integer> teamBySlot,
            List<Integer> teamScores,
            ArenaMap map,
            GameRules rules,
            ReconRules mode,
            ControlTiming timing,
            String roundId) {
        this(
                self,
                team,
                life,
                me,
                visibleTanks,
                projectiles,
                cargo,
                depots,
                sources,
                teamBySlot,
                teamScores,
                map,
                rules,
                mode,
                timing,
                roundId,
                null);
    }

    public ReconObservation {
        Objects.requireNonNull(me, "me");
        if (roundId == null || roundId.isBlank()) {
            throw new IllegalArgumentException("roundId is required");
        }
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(timing, "timing");
        visibleTanks = List.copyOf(visibleTanks);
        projectiles = List.copyOf(projectiles);
        cargo = List.copyOf(cargo);
        depots = List.copyOf(depots);
        sources = List.copyOf(sources);
        teamBySlot = List.copyOf(teamBySlot);
        teamScores = List.copyOf(teamScores);
        if (team != mode.teamOf(self)
                || me.slot() != self
                || life < 0
                || map.spawns().size() != mode.capacity()
                || teamBySlot.size() != mode.capacity()
                || teamScores.size() != mode.teams()) {
            throw new IllegalArgumentException("Inconsistent observer identity or roster");
        }
        for (int slot = 0; slot < teamBySlot.size(); slot++) {
            if (teamBySlot.get(slot) != mode.teamOf(slot)) {
                throw new IllegalArgumentException("Inconsistent team assignment");
            }
        }
        var slots = new HashSet<Integer>();
        for (Tank tank : visibleTanks) {
            if (tank.slot() == self || tank.slot() >= mode.capacity() || !slots.add(tank.slot())) {
                throw new IllegalArgumentException("Invalid visible tank list");
            }
        }
    }

    public List<Tank> opponents() {
        return visibleTanks.stream()
                .filter(tank -> tank.health() > 0 && mode.teamOf(tank.slot()) != team)
                .toList();
    }

    public List<Tank> allies() {
        return visibleTanks.stream()
                .filter(tank -> tank.health() > 0 && mode.teamOf(tank.slot()) == team)
                .toList();
    }
}

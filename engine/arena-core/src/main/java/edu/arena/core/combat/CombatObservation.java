package edu.arena.core.combat;

import edu.arena.core.bot.ControlTiming;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.Projectile;
import edu.arena.core.model.Tank;
import edu.arena.core.recon.ReconObservation;
import java.util.List;

/**
 * One personal snapshot of independent combat. Missing tanks/stocks are unobserved, not known to be
 * absent. Slots identify positions in the current server roster, not permanent accounts. A new life
 * creates a new bot instance. No alliance, coordinator or network channel is supplied.
 *
 * @param self own slot in this arena
 * @param life own life counter; changes after respawn
 * @param roundId server round identity; discard observations from previous rounds
 * @param me own authoritative tank at timing.observedTick()
 * @param visibleTanks other currently visible, living tanks; every one can deal damage
 * @param projectiles currently visible projectiles
 * @param equipment own remaining ammunition, turret upgrade and repair progress
 * @param supplies currently visible field stocks; health zero means destroyed
 * @param scores public personal points by slot; negative values are valid
 * @param map public static walls and geometry, without hidden dynamic entities
 * @param rules current physics settings; do not hardcode their initial values
 * @param timing common decision/application window; networking must not block decide
 * @param maxAmmunition ammunition capacity
 * @param interactionRadius distance for automatic stock interaction, requiring clear line of sight
 * @param repairDurationTicks stationary ticks required to restore one HP from a repair stock
 * @param upgradedTurretFactor multiplier of rules.turretSpeed() while the module is installed
 */
public record CombatObservation(
        int self,
        long life,
        String roundId,
        Tank me,
        List<Tank> visibleTanks,
        List<Projectile> projectiles,
        Equipment equipment,
        List<Supply> supplies,
        List<Long> scores,
        ArenaMap map,
        GameRules rules,
        ControlTiming timing,
        int maxAmmunition,
        double interactionRadius,
        int repairDurationTicks,
        double upgradedTurretFactor) {
    public CombatObservation {
        visibleTanks = List.copyOf(visibleTanks);
        projectiles = List.copyOf(projectiles);
        supplies = List.copyOf(supplies);
        scores = List.copyOf(scores);
    }

    public static CombatObservation from(ReconObservation view) {
        CombatView combat = view.combat();
        if (combat == null) {
            throw new IllegalArgumentException(
                    "Сервер должен использовать config/combat.properties");
        }
        return new CombatObservation(
                view.self(),
                view.life(),
                view.roundId(),
                view.me(),
                view.visibleTanks(),
                view.projectiles(),
                combat.equipment(),
                combat.visibleSupplies(),
                combat.scores(),
                view.map(),
                view.rules(),
                view.timing(),
                combat.maxAmmunition(),
                combat.interactionRadius(),
                combat.repairDurationTicks(),
                combat.upgradedTurretFactor());
    }
}

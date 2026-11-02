package edu.arena.core.engine;

import edu.arena.core.combat.CombatState;
import edu.arena.core.combat.Equipment;
import edu.arena.core.combat.Supply;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.Elimination;
import edu.arena.core.model.EventType;
import edu.arena.core.model.GameEvent;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Independent combat economy. Mutates only local copies within one authoritative step. */
public final class CombatMechanics {
    private CombatMechanics() {}

    public static CombatState initial(ArenaMap map, GameRules rules) {
        var supplies = new ArrayList<Supply>();
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 4; column++) {
                var position = new Vec2(map.width() * column / 5, map.height() * row / 5);
                if (!ArenaGeometry.clearPath(map, position, position, rules.tankRadius() + 15)) {
                    continue;
                }
                int id = supplies.size();
                Supply.Kind kind = Supply.Kind.values()[id % 3];
                int capacity =
                        kind == Supply.Kind.AMMUNITION ? 12 : kind == Supply.Kind.REPAIR ? 3 : 1;
                supplies.add(
                        new Supply(
                                id,
                                kind,
                                position,
                                capacity,
                                capacity,
                                3,
                                refillPeriod(kind, rules.tickRate())));
            }
        }
        return new CombatState(
                Collections.nCopies(map.spawns().size(), Equipment.fresh()),
                supplies,
                Collections.nCopies(map.spawns().size(), 0L));
    }

    public static GameState finish(
            GameState before,
            GameState after,
            List<Control> controls,
            List<Supply> damagedSupplies,
            GameRules rules,
            ArenaMap map) {
        var equipment = new ArrayList<>(before.combat().equipment());
        var tanks = new ArrayList<>(after.tanks());
        var supplies = new ArrayList<>(damagedSupplies);
        var events = new ArrayList<>(after.events());
        var eliminations = new ArrayList<>(after.eliminations());
        var scores = new ArrayList<>(before.combat().scores());

        for (GameEvent event : after.events()) {
            if (event.type() == EventType.SHOT) {
                Equipment item = equipment.get(event.actor());
                equipment.set(
                        event.actor(),
                        new Equipment(
                                Math.max(0, item.ammunition() - 1),
                                item.turretUpgrade(),
                                item.repairTicks()));
            }
        }
        // Simultaneous lethal hits still award exactly one frag. The cyclic priority changes
        // every tick and victim; projectile/list processing order never chooses the winner.
        for (int index = 0; index < events.size(); index++) {
            GameEvent event = events.get(index);
            if (event.type() != EventType.DESTROYED
                    || before.tanks().get(event.target()).health() == 0) {
                continue;
            }
            int victim = event.target();
            int priority = Math.floorMod(after.tick() + victim, tanks.size());
            int actor = event.actor();
            if (actor < 0) {
                actor =
                        after.events().stream()
                                .filter(
                                        hit ->
                                                hit.type() == EventType.HIT
                                                        && hit.target() == victim
                                                        && hit.actor() >= 0
                                                        && hit.actor() != victim)
                                .mapToInt(GameEvent::actor)
                                .boxed()
                                .distinct()
                                .min(
                                        Comparator.comparingInt(
                                                slot ->
                                                        Math.floorMod(
                                                                slot - priority, tanks.size())))
                                .orElse(-1);
            }
            scores.set(
                    victim, Math.subtractExact(scores.get(victim), CombatState.POINTS_PER_DEATH));
            if (actor >= 0 && actor != victim) {
                scores.set(actor, Math.addExact(scores.get(actor), CombatState.POINTS_PER_DEATH));
            }
            events.set(index, new GameEvent(event.type(), actor, victim, event.position()));
            for (int e = 0; e < eliminations.size(); e++) {
                Elimination elimination = eliminations.get(e);
                if (elimination.slot() == victim && elimination.tick() == after.tick()) {
                    eliminations.set(
                            e, new Elimination(victim, after.tick(), elimination.reason(), actor));
                }
            }
            equipment.set(victim, new Equipment(0, false, 0));
        }

        for (int slot = 0; slot < tanks.size(); slot++) {
            var tank = tanks.get(slot);
            var command = controls.get(slot);
            var item = equipment.get(slot);
            boolean hurt =
                    after.events().stream()
                            .anyMatch(
                                    event ->
                                            event.type() == EventType.HIT
                                                    && event.target() == tank.slot());
            boolean canRepair =
                    tank.health() > 0
                            && tank.health() < rules.hitPoints()
                            && command.throttle() == 0
                            && command.turn() == 0
                            && !command.fire()
                            && !hurt
                            && tank.velocity().length() < 0.001
                            && supplies.stream()
                                    .anyMatch(
                                            supply ->
                                                    supply.kind() == Supply.Kind.REPAIR
                                                            && supply.health() > 0
                                                            && supply.stock() > 0
                                                            && inReach(tank, supply, map));
            equipment.set(
                    slot,
                    new Equipment(
                            item.ammunition(),
                            item.turretUpgrade(),
                            canRepair
                                    ? Math.min(3 * rules.tickRate(), item.repairTicks() + 1)
                                    : 0));
        }

        for (int index = 0; index < supplies.size(); index++) {
            Supply supply = supplies.get(index);
            if (after.tick() >= supply.nextRefillTick()) {
                supply =
                        new Supply(
                                supply.id(),
                                supply.kind(),
                                supply.position(),
                                Math.min(supply.capacity(), supply.stock() + 1),
                                supply.capacity(),
                                3,
                                after.tick() + refillPeriod(supply.kind(), rules.tickRate()));
            }
            if (supply.health() == 0) {
                supplies.set(index, supply);
                continue;
            }
            int priority = Math.floorMod(after.tick() + supply.id(), tanks.size());
            for (int offset = 0; offset < tanks.size() && supply.stock() > 0; offset++) {
                int slot = (priority + offset) % tanks.size();
                Tank tank = tanks.get(slot);
                if (tank.health() == 0 || !inReach(tank, supply, map)) {
                    continue;
                }
                Equipment item = equipment.get(slot);
                boolean used = false;
                switch (supply.kind()) {
                    case AMMUNITION -> {
                        if (item.ammunition() < CombatState.MAX_AMMUNITION
                                && after.tick() % Math.max(1, rules.tickRate() / 4) == 0) {
                            item =
                                    new Equipment(
                                            item.ammunition() + 1,
                                            item.turretUpgrade(),
                                            item.repairTicks());
                            used = true;
                        }
                    }
                    case REPAIR -> {
                        if (tank.health() < rules.hitPoints()
                                && item.repairTicks() >= 3 * rules.tickRate()) {
                            tanks.set(
                                    slot,
                                    new Tank(
                                            slot,
                                            tank.position(),
                                            tank.velocity(),
                                            tank.heading(),
                                            tank.gunHeading(),
                                            tank.health() + 1,
                                            tank.cooldownTicks(),
                                            tank.shieldTicks(),
                                            tank.shieldCooldownTicks()));
                            item = new Equipment(item.ammunition(), item.turretUpgrade(), 0);
                            used = true;
                        }
                    }
                    case TURRET_MODULE -> {
                        if (!item.turretUpgrade()) {
                            item = new Equipment(item.ammunition(), true, item.repairTicks());
                            used = true;
                        }
                    }
                }
                if (used) {
                    supply = supply.consume();
                    equipment.set(slot, item);
                }
            }
            supplies.set(index, supply);
        }
        return new GameState(
                after.tick(),
                tanks,
                after.projectiles(),
                events,
                after.outcome(),
                eliminations,
                after.safeRadius(),
                after.delivery(),
                new CombatState(equipment, supplies, scores));
    }

    private static boolean inReach(Tank tank, Supply supply, ArenaMap map) {
        return tank.position().distance(supply.position()) <= CombatState.INTERACTION_RADIUS
                && ArenaGeometry.clearPath(map, tank.position(), supply.position(), 0);
    }

    private static int refillPeriod(Supply.Kind kind, int tickRate) {
        return tickRate
                * switch (kind) {
                    case AMMUNITION -> 2;
                    case REPAIR -> 10;
                    case TURRET_MODULE -> 30;
                };
    }
}

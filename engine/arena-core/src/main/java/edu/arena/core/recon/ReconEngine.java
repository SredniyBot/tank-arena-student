package edu.arena.core.recon;

import edu.arena.core.bot.ControlTiming;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.engine.ArenaGeometry;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.DeliveryEvent;
import edu.arena.core.model.DeliveryState;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Outcome;
import edu.arena.core.model.Spawn;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Pure continuous team delivery using the existing swept-collision physics. Friendly fire follows
 * legacy combat rules. The host owns command deadlines; this engine owns scoring and respawn.
 */
public final class ReconEngine {
    private final GameRules rules;
    private final ArenaMap map;
    private final ReconRules mode;
    private final ArenaEngine combat;
    private final ReconDelivery delivery;
    private final boolean controlZones;
    private final ReconObjective objective;

    public ReconEngine(GameRules rules, ArenaMap map, ReconRules mode) {
        this(rules, map, mode, 4);
    }

    /** Select two or four symmetric cargo sources before the match starts. */
    public ReconEngine(GameRules rules, ArenaMap map, ReconRules mode, int sourceCount) {
        this(rules, map, mode, sourceCount, false);
    }

    /** Control mode accrues one team-tick per uncontested zone and simulation step. */
    public ReconEngine(
            GameRules rules, ArenaMap map, ReconRules mode, int sourceCount, boolean controlZones) {
        this(
                rules,
                map,
                mode,
                sourceCount,
                controlZones ? ReconObjective.CONTROL_ZONES : ReconObjective.DELIVERY);
    }

    public ReconEngine(
            GameRules rules,
            ArenaMap map,
            ReconRules mode,
            int sourceCount,
            ReconObjective objective) {
        boolean controlZones = objective == ReconObjective.CONTROL_ZONES;
        this.objective = Objects.requireNonNull(objective, "objective");
        this.controlZones = controlZones;
        if (controlZones && sourceCount != 4) {
            throw new IllegalArgumentException("Control mode requires four zones");
        }
        this.rules = Objects.requireNonNull(rules, "rules");
        this.map = Objects.requireNonNull(map, "map");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (map.spawns().size() != mode.capacity()) {
            throw new IllegalArgumentException("Map capacity differs from team roster");
        }
        combat = new ArenaEngine(rules, map);
        delivery =
                objective == ReconObjective.COMBAT
                        ? null
                        : new ReconDelivery(rules, map, mode, sourceCount, controlZones);
    }

    public GameRules rules() {
        return rules;
    }

    public ArenaMap map() {
        return map;
    }

    public ReconRules mode() {
        return mode;
    }

    /** All slots start alive in life one, without entry protection, for controlled experiments. */
    public ReconState initialState() {
        return state(initializeCombat(combat.initialState().withDelivery(initialDelivery())), 1);
    }

    /**
     * Joining an empty slot occurs after its first active step and grants normal spawn protection.
     */
    public ReconState emptyState() {
        return state(initializeCombat(combat.emptyState().withDelivery(initialDelivery())), 0);
    }

    private GameState initializeCombat(GameState world) {
        return objective == ReconObjective.COMBAT
                ? world.withCombat(edu.arena.core.engine.CombatMechanics.initial(map, rules))
                : world;
    }

    private DeliveryState initialDelivery() {
        return delivery == null ? null : delivery.initial();
    }

    /** Start a fresh round at the same simulation tick, preserving per-slot life counters. */
    public ReconState resetRound(ReconState previous, Set<Integer> activeSlots) {
        validate(previous);
        activeSlots = Set.copyOf(activeSlots);
        activeSlots.forEach(mode::teamOf);
        GameState empty = emptyState().arena();
        GameState world =
                new GameState(
                        previous.arena().tick(),
                        empty.tanks(),
                        empty.projectiles(),
                        empty.events(),
                        Outcome.RUNNING,
                        empty.eliminations(),
                        empty.safeRadius(),
                        empty.delivery(),
                        empty.combat());
        var lives = new ArrayList<>(previous.lives());
        for (int slot = 0; slot < mode.capacity(); slot++) {
            if (activeSlots.contains(slot)) {
                var spawned = respawn(world, slot);
                if (spawned == world) {
                    throw new IllegalStateException("No safe starting position for slot " + slot);
                }
                world = spawned;
                lives.set(slot, Math.addExact(lives.get(slot), 1));
            }
        }
        return new ReconState(
                world,
                Collections.nCopies(mode.teams(), 0),
                Collections.nCopies(mode.capacity(), 0),
                lives);
    }

    public ReconState step(ReconState state, List<Control> controls, Set<Integer> activeSlots) {
        validate(state);
        controls = List.copyOf(controls);
        activeSlots = Set.copyOf(activeSlots);
        if (controls.size() != mode.capacity()) {
            throw new IllegalArgumentException("Expected one control per slot");
        }
        activeSlots.forEach(mode::teamOf);
        GameState before = state.arena();
        var due = new ArrayList<>(state.respawnAt());
        var lives = new ArrayList<>(state.lives());
        for (int slot = 0; slot < mode.capacity(); slot++) {
            if (!activeSlots.contains(slot)) {
                int absent = slot;
                boolean inactive =
                        before.eliminations().stream()
                                .anyMatch(
                                        item ->
                                                item.slot() == absent
                                                        && item.reason().equals("INACTIVE"));
                if (!inactive) {
                    before = combat.vacate(before, slot);
                }
                due.set(slot, 0);
            }
        }
        Set<Integer> carrying =
                (before.delivery() == null
                                ? List.<edu.arena.core.model.Cargo>of()
                                : before.delivery().cargo())
                        .stream()
                                .filter(item -> item.carrierSlot() >= 0)
                                .map(item -> item.carrierSlot())
                                .collect(java.util.stream.Collectors.toSet());
        var effective = new ArrayList<Control>();
        for (int slot = 0; slot < mode.capacity(); slot++) {
            Control command = controls.get(slot);
            if (before.tanks().get(slot).health() == 0) {
                command = Control.idle(before.tanks().get(slot).position());
            } else if (carrying.contains(slot)) {
                command =
                        new Control(
                                command.throttle() * mode.cargoSpeedFactor(),
                                command.turn(),
                                command.aim(),
                                false,
                                command.shield());
            }
            effective.add(command);
        }
        GameState next = combat.stepContinuous(before, effective);
        if (objective == ReconObjective.DELIVERY) {
            next = next.withDelivery(delivery.step(before.delivery(), next, activeSlots));
        }
        var scores = new ArrayList<>(state.teamScores());
        var controlTicks = new ArrayList<>(state.controlTicks());
        if (controlZones) {
            for (var zone : delivery.sources()) {
                int owner = -1;
                boolean contested = false;
                for (var tank : next.tanks()) {
                    if (tank.health() <= 0
                            || !activeSlots.contains(tank.slot())
                            || tank.position().distance(zone) > 100) {
                        continue;
                    }
                    int team = mode.teamOf(tank.slot());
                    if (owner >= 0 && owner != team) {
                        contested = true;
                        break;
                    }
                    owner = team;
                }
                if (owner >= 0 && !contested) {
                    int accumulated = Math.addExact(controlTicks.get(owner), 1);
                    if (accumulated >= rules.tickRate()) {
                        scores.set(owner, Math.addExact(scores.get(owner), 1));
                        accumulated -= rules.tickRate();
                    }
                    controlTicks.set(owner, accumulated);
                }
            }
        }
        for (var event :
                next.delivery() == null ? List.<DeliveryEvent>of() : next.delivery().events()) {
            if (event.kind() == DeliveryEvent.Kind.DELIVERED) {
                int team = mode.teamOf(event.slot());
                scores.set(team, Math.addExact(scores.get(team), 1));
            }
        }
        for (int slot = 0; slot < mode.capacity(); slot++) {
            if (before.tanks().get(slot).health() > 0 && next.tanks().get(slot).health() == 0) {
                due.set(slot, Math.addExact(next.tick(), mode.respawnTicks()));
            }
        }
        for (int team = 0; team < mode.teams(); team++) {
            int first = mode.firstSlot(team);
            var ready = new ArrayList<Integer>();
            for (int slot = first; slot < first + mode.sizeOfTeam(team); slot++) {
                if (activeSlots.contains(slot)
                        && next.tanks().get(slot).health() == 0
                        && due.get(slot) <= next.tick()) {
                    ready.add(slot);
                }
            }
            if (ready.isEmpty()) {
                continue;
            }
            // The existing life counters make this priority deterministic across replays.
            // Rotating only over eligible slots avoids favouring one slot when a base is crowded.
            int start = 0;
            for (int slot = first; slot < first + mode.sizeOfTeam(team); slot++) {
                start = (int) ((start + state.lives().get(slot) % ready.size()) % ready.size());
            }
            for (int index = 0; index < ready.size(); index++) {
                int slot = ready.get((start + index) % ready.size());
                GameState respawned = respawn(next, slot);
                if (respawned != next) {
                    next = respawned;
                    lives.set(slot, Math.addExact(lives.get(slot), 1));
                    due.set(slot, 0);
                }
            }
        }
        return new ReconState(next, scores, due, lives, controlTicks);
    }

    public ReconObservation observe(ReconState state, int slot, ControlTiming timing) {
        return observe(state, slot, timing, "offline");
    }

    public ReconObservation observe(
            ReconState state, int slot, ControlTiming timing, String roundId) {
        validate(state);
        int team = mode.teamOf(slot);
        Objects.requireNonNull(timing, "timing");
        if (timing.observedTick() != state.arena().tick()) {
            throw new IllegalArgumentException("Observation timing describes another world tick");
        }
        var world = state.arena();
        Tank me = world.tanks().get(slot);
        boolean alive = me.health() > 0;
        var tanks =
                world.tanks().stream()
                        .filter(
                                tank ->
                                        alive
                                                && tank.slot() != slot
                                                && tank.health() > 0
                                                && isVisible(me.position(), tank.position()))
                        .toList();
        var projectiles =
                world.projectiles().stream()
                        .filter(
                                projectile ->
                                        alive && isVisible(me.position(), projectile.position()))
                        .toList();
        var cargo =
                (world.delivery() == null
                                ? List.<edu.arena.core.model.Cargo>of()
                                : world.delivery().cargo())
                        .stream()
                                .filter(
                                        item ->
                                                alive
                                                        && item.availableAtTick() <= world.tick()
                                                        && (item.carrierSlot() == slot
                                                                || isVisible(
                                                                        me.position(),
                                                                        item.position())))
                                .filter(
                                        item ->
                                                item.carrierSlot() < 0
                                                        || item.carrierSlot() == slot
                                                        || tanks.stream()
                                                                .anyMatch(
                                                                        tank ->
                                                                                tank.slot()
                                                                                        == item
                                                                                                .carrierSlot()))
                                .toList();
        return new ReconObservation(
                slot,
                team,
                state.lives().get(slot),
                me,
                tanks,
                projectiles,
                cargo,
                delivery == null ? List.of() : delivery.depots(),
                delivery == null ? List.of() : delivery.sources(),
                IntStream.range(0, mode.capacity()).map(mode::teamOf).boxed().toList(),
                state.teamScores(),
                map,
                rules,
                mode,
                timing,
                roundId,
                world.combat() == null
                        ? null
                        : new edu.arena.core.combat.CombatView(
                                world.combat().equipment().get(slot),
                                world.combat().supplies().stream()
                                        .filter(
                                                supply ->
                                                        alive
                                                                && isVisible(
                                                                        me.position(),
                                                                        supply.position()))
                                        .toList(),
                                world.combat().scores(),
                                edu.arena.core.combat.CombatState.MAX_AMMUNITION,
                                edu.arena.core.combat.CombatState.INTERACTION_RADIUS,
                                3 * rules.tickRate(),
                                edu.arena.core.combat.CombatState.UPGRADED_TURRET_FACTOR));
    }

    /** Center-point visibility includes the range boundary; a wall touching the ray occludes it. */
    public boolean isVisible(Vec2 from, Vec2 point) {
        return from.distance(point) <= mode.visionRadius()
                && ArenaGeometry.clearPath(map, from, point, 0);
    }

    private ReconState state(GameState world, long life) {
        return new ReconState(
                world,
                Collections.nCopies(mode.teams(), 0),
                Collections.nCopies(mode.capacity(), 0),
                Collections.nCopies(mode.capacity(), life));
    }

    private void validate(ReconState state) {
        Objects.requireNonNull(state, "state");
        if (state.arena().tanks().size() != mode.capacity()
                || (objective == ReconObjective.COMBAT) != (state.arena().delivery() == null)
                || state.teamScores().size() != mode.teams()
                || state.controlTicks().stream().anyMatch(value -> value >= rules.tickRate())) {
            throw new IllegalArgumentException("State belongs to another team configuration");
        }
    }

    private GameState respawn(GameState state, int slot) {
        if (objective == ReconObjective.COMBAT) {
            return combat.respawn(state, slot, mode.spawnProtectionTicks());
        }
        int first = mode.firstSlot(mode.teamOf(slot));
        int teamSize = mode.sizeOfTeam(mode.teamOf(slot));
        for (int offset = 0; offset < teamSize; offset++) {
            int candidateSlot = first + (slot - first + offset) % teamSize;
            Spawn spawn = map.spawns().get(candidateSlot);
            Vec2 position = spawn.position();
            boolean occupied =
                    state.tanks().stream()
                            .anyMatch(
                                    tank ->
                                            tank.health() > 0
                                                    && tank.position().distance(position)
                                                            < 2 * rules.tankRadius());
            if (occupied || !ArenaGeometry.clearPath(map, position, position, rules.tankRadius())) {
                continue;
            }
            var tanks = new ArrayList<>(state.tanks());
            tanks.set(
                    slot,
                    new Tank(
                            slot,
                            position,
                            new Vec2(0, 0),
                            spawn.heading(),
                            spawn.heading(),
                            rules.hitPoints(),
                            0,
                            mode.spawnProtectionTicks(),
                            Math.max(mode.spawnProtectionTicks(), rules.shieldCooldownTicks())));
            return new GameState(
                    state.tick(),
                    tanks,
                    state.projectiles().stream().filter(item -> item.owner() != slot).toList(),
                    state.events(),
                    Outcome.RUNNING,
                    state.eliminations().stream().filter(item -> item.slot() != slot).toList(),
                    state.safeRadius(),
                    state.delivery());
        }
        return state;
    }
}

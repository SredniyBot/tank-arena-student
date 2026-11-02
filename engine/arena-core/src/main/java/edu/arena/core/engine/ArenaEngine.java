package edu.arena.core.engine;

import edu.arena.core.combat.CombatState;
import edu.arena.core.combat.Supply;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.Elimination;
import edu.arena.core.model.EventType;
import edu.arena.core.model.GameEvent;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Outcome;
import edu.arena.core.model.Placement;
import edu.arena.core.model.Projectile;
import edu.arena.core.model.Spawn;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import edu.arena.core.model.Wall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure fixed-step simulation: every participant acts from one immutable shared snapshot. */
public final class ArenaEngine {
    private final GameRules rules;
    private final ArenaMap map;
    private final double dt;
    private final Vec2 centre;
    private final double initialRadius;
    private final List<Integer> protectedTeams;

    public ArenaEngine(GameRules rules, ArenaMap map) {
        this(rules, map, List.of());
    }

    /**
     * Allied tanks intercept projectiles without taking friendly damage. Empty means free-for-all.
     */
    public ArenaEngine(GameRules rules, ArenaMap map, List<Integer> protectedTeams) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.map = Objects.requireNonNull(map, "map");
        this.protectedTeams = List.copyOf(protectedTeams);
        if (!this.protectedTeams.isEmpty()
                && (this.protectedTeams.size() != map.spawns().size()
                        || this.protectedTeams.stream().anyMatch(team -> team < 0))) {
            throw new IllegalArgumentException("Team roster must cover every slot");
        }
        dt = 1.0 / rules.tickRate();
        centre = new Vec2(map.width() / 2, map.height() / 2);
        initialRadius = Math.min(map.width(), map.height()) / 2 - 30;
        if (initialRadius <= 0) {
            throw new IllegalArgumentException("Map is too small for the zone");
        }
        validateSpawns();
    }

    public GameState initialState() {
        var tanks = new ArrayList<Tank>(map.spawns().size());
        for (int slot = 0; slot < map.spawns().size(); slot++) {
            Spawn spawn = map.spawns().get(slot);
            tanks.add(
                    new Tank(
                            slot,
                            spawn.position(),
                            new Vec2(0, 0),
                            spawn.heading(),
                            spawn.heading(),
                            rules.hitPoints(),
                            0));
        }
        return new GameState(
                0, tanks, List.of(), List.of(), Outcome.RUNNING, List.of(), initialRadius);
    }

    /** Fixed-capacity continuous arena. Empty slots have no collision body and cannot act. */
    public GameState emptyState() {
        var initial = initialState();
        var tanks = initial.tanks().stream().map(tank -> withHealth(tank, 0)).toList();
        var inactive =
                tanks.stream().map(tank -> new Elimination(tank.slot(), 0, "INACTIVE")).toList();
        return new GameState(
                0, tanks, List.of(), List.of(), Outcome.RUNNING, inactive, continuousRadius());
    }

    /** Continuous combat has no shrinking zone, last-survivor finish, or match time limit. */
    public GameState stepContinuous(GameState state, List<Control> controls) {
        return stepContinuous(state, controls, Map.of());
    }

    public GameState stepContinuous(
            GameState state, List<Control> controls, Map<Integer, String> exclusions) {
        return step(state, controls, exclusions, true);
    }

    /**
     * Places a dead slot in a clear location far from living tanks and away from its previous life.
     * Returns the unchanged state if no safe candidate is free; the host can retry at a later tick.
     * The host owns the respawn delay, membership and cumulative scoring.
     */
    public GameState respawn(GameState state, int slot, int protectionTicks) {
        validateSlot(state, slot);
        if (protectionTicks < 0) {
            throw new IllegalArgumentException("Protection cannot be negative");
        }
        if (state.tanks().get(slot).health() > 0) {
            throw new IllegalArgumentException("Slot is already alive");
        }
        Vec2 position = spawnPosition(state, slot);
        if (position == null) {
            return state;
        }
        double heading = Math.atan2(centre.y() - position.y(), centre.x() - position.x());
        var tanks = new ArrayList<>(state.tanks());
        tanks.set(
                slot,
                new Tank(
                        slot,
                        position,
                        new Vec2(0, 0),
                        heading,
                        heading,
                        rules.hitPoints(),
                        0,
                        protectionTicks,
                        Math.max(protectionTicks, rules.shieldCooldownTicks())));
        return new GameState(
                state.tick(),
                tanks,
                state.projectiles().stream()
                        .filter(projectile -> projectile.owner() != slot)
                        .toList(),
                state.events(),
                Outcome.RUNNING,
                state.eliminations().stream().filter(item -> item.slot() != slot).toList(),
                continuousRadius(),
                DeliveryEngine.release(
                        state.delivery(), slot, state.tanks().get(slot).position(), state.tick()),
                state.combat() == null ? null : state.combat().newLife(slot));
    }

    /**
     * Clears an identity at a simulation boundary, including every projectile owned by that slot.
     */
    public GameState vacate(GameState state, int slot) {
        validateSlot(state, slot);
        var tanks = new ArrayList<>(state.tanks());
        tanks.set(slot, withHealth(tanks.get(slot), 0));
        var inactive =
                new ArrayList<>(
                        state.eliminations().stream().filter(item -> item.slot() != slot).toList());
        inactive.add(new Elimination(slot, state.tick(), "INACTIVE"));
        return new GameState(
                state.tick(),
                tanks,
                state.projectiles().stream()
                        .filter(projectile -> projectile.owner() != slot)
                        .toList(),
                state.events().stream()
                        .filter(event -> event.actor() != slot && event.target() != slot)
                        .toList(),
                Outcome.RUNNING,
                inactive,
                continuousRadius(),
                DeliveryEngine.release(
                        state.delivery(), slot, state.tanks().get(slot).position(), state.tick()),
                state.combat() == null ? null : state.combat().newLife(slot));
    }

    /** All movement, hits and zone damage are resolved before any participant is eliminated. */
    public GameState step(GameState state, List<Control> controls) {
        return step(state, controls, Map.of());
    }

    /**
     * Applies boundary exclusions and physics in one transition, preserving the published input
     * frame.
     */
    public GameState step(
            GameState state, List<Control> controls, Map<Integer, String> exclusions) {
        return step(state, controls, exclusions, false);
    }

    private GameState step(
            GameState state,
            List<Control> controls,
            Map<Integer, String> exclusions,
            boolean continuous) {
        validateRoster(state);
        controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        int participants = state.tanks().size();
        if (controls.size() != participants) {
            throw new IllegalArgumentException("Expected one control per participant");
        }
        exclusions = Map.copyOf(Objects.requireNonNull(exclusions, "exclusions"));
        for (var exclusion : exclusions.entrySet()) {
            if (exclusion.getKey() < 0 || exclusion.getKey() >= participants) {
                throw new IllegalArgumentException("Excluded participant is outside the roster");
            }
            new Elimination(exclusion.getKey(), state.tick(), exclusion.getValue());
        }
        if (!continuous && state.outcome() == Outcome.FINISHED) {
            return state;
        }
        if (!continuous && state.tick() >= rules.maxTicks()) {
            return new GameState(
                    state.tick(),
                    state.tanks(),
                    state.projectiles(),
                    List.of(),
                    Outcome.FINISHED,
                    state.eliminations(),
                    radiusAt(state.tick()),
                    state.delivery(),
                    state.combat());
        }

        // Exclusions become effective at this boundary without exposing a second version of tick T.
        var previous = new ArrayList<>(state.tanks());
        for (int slot : exclusions.keySet()) {
            previous.set(slot, withHealth(previous.get(slot), 0));
        }
        var supplies =
                new ArrayList<Supply>(
                        state.combat() == null ? List.of() : state.combat().supplies());
        var events = new ArrayList<GameEvent>();
        boolean[] shielded = new boolean[participants];
        boolean[] activatedShield = new boolean[participants];
        Tank[] moved = new Tank[participants];
        for (int slot = 0; slot < participants; slot++) {
            Tank before = previous.get(slot);
            boolean activate =
                    before.health() > 0
                            && controls.get(slot).shield()
                            && before.shieldCooldownTicks() <= 1;
            activatedShield[slot] = activate;
            shielded[slot] = before.health() > 0 && (activate || before.shieldTicks() > 0);
            moved[slot] =
                    move(
                            before,
                            controls.get(slot),
                            activate,
                            state.combat() != null
                                    && state.combat().equipment().get(slot).turretUpgrade());
        }
        resolveTankContacts(previous, moved);
        var projectiles = new ArrayList<>(state.projectiles());
        Set<Integer> excludedSlots = exclusions.keySet();
        if (continuous) {
            projectiles.removeIf(projectile -> excludedSlots.contains(projectile.owner()));
        }
        for (int slot = 0; slot < participants; slot++) {
            Tank before = previous.get(slot);
            Tank after = moved[slot];
            if (activatedShield[slot]) {
                events.add(new GameEvent(EventType.SHIELD_ACTIVATED, slot, slot, after.position()));
            }
            if (before.health() > 0
                    && !shielded[slot]
                    && controls.get(slot).fire()
                    && (state.combat() == null
                            || state.combat().equipment().get(slot).ammunition() > 0)
                    && after.cooldownTicks() == 0) {
                Vec2 velocity = direction(after.gunHeading()).scale(rules.bulletSpeed());
                projectiles.add(
                        new Projectile(
                                64L * state.tick() + slot, slot, before.position(), velocity));
                events.add(new GameEvent(EventType.SHOT, slot, -1, before.position()));
                moved[slot] = withCooldown(after, rules.reloadTicks());
            }
        }

        int[] damage = new int[participants];
        int[] attackers = new int[participants];
        Arrays.fill(attackers, -1);
        var surviving = new ArrayList<Projectile>();
        for (Projectile projectile : projectiles) {
            advanceProjectile(
                    projectile,
                    previous,
                    moved,
                    shielded,
                    damage,
                    attackers,
                    events,
                    surviving,
                    supplies,
                    state.tick() + 1);
        }

        int tick = state.tick() + 1;
        double radius = continuous ? continuousRadius() : radiusAt(tick);
        var tanks = new ArrayList<Tank>(participants);
        var eliminations = new ArrayList<>(state.eliminations());
        for (int slot = 0; slot < participants; slot++) {
            Tank tank = moved[slot];
            boolean zoneHit =
                    !continuous
                            && tank.health() > 0
                            && tick % rules.zoneDamageIntervalTicks() == 0
                            && tank.position().distance(centre) + rules.tankRadius() > radius;
            if (zoneHit) {
                events.add(new GameEvent(EventType.ZONE, -1, slot, tank.position()));
            }
            int health = Math.max(0, tank.health() - damage[slot] - (zoneHit ? 1 : 0));
            tanks.add(withHealth(tank, health));
            if (exclusions.containsKey(slot) && state.tanks().get(slot).health() > 0) {
                eliminations.add(new Elimination(slot, tick, exclusions.get(slot)));
                events.add(new GameEvent(EventType.DESTROYED, -1, slot, tank.position()));
            } else if (tank.health() > 0 && health == 0) {
                String reason = damage[slot] >= tank.health() ? "DESTROYED" : "ZONE";
                // Several attackers, or combined zone damage, have no single credited killer.
                int actor = zoneHit || attackers[slot] < 0 ? -1 : attackers[slot];
                eliminations.add(new Elimination(slot, tick, reason, actor));
                events.add(new GameEvent(EventType.DESTROYED, actor, slot, tank.position()));
            }
        }
        var result =
                new GameState(
                        tick,
                        tanks,
                        surviving,
                        events,
                        continuous ? Outcome.RUNNING : result(tanks, tick),
                        eliminations,
                        radius,
                        state.delivery(),
                        state.combat());
        return state.combat() == null
                ? result
                : CombatMechanics.finish(state, result, controls, supplies, rules, map);
    }

    /**
     * Excludes a batch at one tick; surviving participants continue. Repeated exclusions are
     * harmless.
     */
    public GameState eliminate(GameState state, Set<Integer> slots, String reason) {
        validateRoster(state);
        slots = Set.copyOf(Objects.requireNonNull(slots, "slots"));
        new Elimination(0, state.tick(), reason); // Validate the reason even for an empty batch.
        for (int slot : slots) {
            if (slot < 0 || slot >= state.tanks().size()) {
                throw new IllegalArgumentException("Excluded participant is outside the roster");
            }
        }
        if (state.outcome() == Outcome.FINISHED || slots.isEmpty()) {
            return state;
        }
        var tanks = new ArrayList<>(state.tanks());
        var eliminations = new ArrayList<>(state.eliminations());
        var events = new ArrayList<>(state.events());
        boolean changed = false;
        for (int slot = 0; slot < tanks.size(); slot++) {
            Tank tank = tanks.get(slot);
            if (slots.contains(slot) && tank.health() > 0) {
                tanks.set(slot, withHealth(tank, 0));
                eliminations.add(new Elimination(slot, state.tick(), reason));
                events.add(new GameEvent(EventType.DESTROYED, -1, slot, tank.position()));
                changed = true;
            }
        }
        return changed
                ? new GameState(
                        state.tick(),
                        tanks,
                        state.projectiles(),
                        events,
                        result(tanks, state.tick()),
                        eliminations,
                        state.safeRadius(),
                        state.delivery())
                : state;
    }

    /**
     * Provisional during a round. The host publishes official scores only after durable completion.
     */
    public List<Placement> placements(GameState state) {
        validateRoster(state);
        int[] ticks = new int[state.tanks().size()];
        Arrays.fill(ticks, -1);
        for (Elimination elimination : state.eliminations()) {
            ticks[elimination.slot()] = elimination.tick();
        }
        var result = new ArrayList<Placement>(ticks.length);
        for (int slot = 0; slot < ticks.length; slot++) {
            int rank = 1;
            int points = 0;
            for (int other : ticks) {
                if (later(other, ticks[slot])) {
                    rank++;
                }
                if (later(ticks[slot], other)) {
                    points++;
                }
            }
            result.add(new Placement(slot, rank, points, ticks[slot]));
        }
        return List.copyOf(result);
    }

    private static boolean later(int first, int second) {
        return first != second && (first == -1 || (second != -1 && first > second));
    }

    private void validateRoster(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.tanks().size() != map.spawns().size()) {
            throw new IllegalArgumentException("State and map participant counts differ");
        }
    }

    private void validateSlot(GameState state, int slot) {
        validateRoster(state);
        if (slot < 0 || slot >= state.tanks().size()) {
            throw new IllegalArgumentException("Slot outside roster");
        }
    }

    private double continuousRadius() {
        return Math.hypot(map.width(), map.height()) / 2 + rules.tankRadius();
    }

    private Vec2 spawnPosition(GameState state, int slot) {
        double margin = 3 * rules.tankRadius();
        double spacing = Math.max(5 * rules.tankRadius(), 80);
        int columns = Math.max(1, (int) ((map.width() - 2 * margin) / spacing));
        int rows = Math.max(1, (int) ((map.height() - 2 * margin) / spacing));
        var candidates = new ArrayList<Vec2>();
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column <= columns; column++) {
                candidates.add(
                        new Vec2(
                                margin + column * (map.width() - 2 * margin) / columns,
                                margin + row * (map.height() - 2 * margin) / rows));
            }
        }
        int first = Math.floorMod(31 * slot + state.tick(), candidates.size());
        Vec2 best = null;
        double bestDistance = -1;
        for (int index = 0; index < candidates.size(); index++) {
            Vec2 candidate = candidates.get((first + index) % candidates.size());
            if (candidate.x() < margin
                    || candidate.x() > map.width() - margin
                    || candidate.y() < margin
                    || candidate.y() > map.height() - margin) {
                continue;
            }
            if (candidate.distance(state.tanks().get(slot).position()) < 4 * rules.tankRadius()) {
                continue;
            }
            if (state.delivery() != null
                    && state.delivery().cargo().stream()
                            .anyMatch(
                                    item ->
                                            item.carrierSlot() < 0
                                                    && item.availableAtTick() <= state.tick()
                                                    && candidate.distance(item.position())
                                                            < Math.max(
                                                                    72, 4 * rules.tankRadius()))) {
                continue;
            }
            if (map.walls().stream()
                    .anyMatch(
                            wall ->
                                    CollisionMath.distanceToWall(candidate, wall)
                                            <= 2 * rules.tankRadius())) {
                continue;
            }
            double closest = Double.POSITIVE_INFINITY;
            for (Tank tank : state.tanks()) {
                if (tank.health() > 0) {
                    closest = Math.min(closest, candidate.distance(tank.position()));
                }
            }
            if (closest <= 2 * rules.tankRadius() || closest <= bestDistance) {
                continue;
            }
            best = candidate;
            bestDistance = closest;
        }
        return best;
    }

    private double radiusAt(int tick) {
        double progress =
                Math.clamp(
                        (double) (tick - rules.zoneStartTick())
                                / (rules.zoneEndTick() - rules.zoneStartTick()),
                        0,
                        1);
        return initialRadius * (1 - progress);
    }

    private Tank move(Tank tank, Control control, boolean activateShield, boolean upgraded) {
        if (tank.health() == 0) {
            return tank;
        }
        double heading = normalize(tank.heading() + control.turn() * rules.turnSpeed() * dt);
        Vec2 aim = control.aim().subtract(tank.position());
        double desiredGun = aim.length() < 1e-12 ? tank.gunHeading() : Math.atan2(aim.y(), aim.x());
        double gun =
                normalize(
                        tank.gunHeading()
                                + Math.clamp(
                                        normalize(desiredGun - tank.gunHeading()),
                                        -rules.turretSpeed()
                                                * dt
                                                * (upgraded
                                                        ? CombatState.UPGRADED_TURRET_FACTOR
                                                        : 1),
                                        rules.turretSpeed()
                                                * dt
                                                * (upgraded
                                                        ? CombatState.UPGRADED_TURRET_FACTOR
                                                        : 1)));
        Vec2 delta = direction(heading).scale(control.throttle() * rules.moveSpeed() * dt);
        double contact = obstacleContact(tank.position(), delta, rules.tankRadius());
        if (contact <= 1) {
            delta = delta.scale(safeFraction(contact, delta.length()));
        }
        return new Tank(
                tank.slot(),
                tank.position().add(delta),
                delta.scale(1 / dt),
                heading,
                gun,
                tank.health(),
                Math.max(0, tank.cooldownTicks() - 1),
                activateShield
                        ? rules.shieldDurationTicks() - 1
                        : Math.max(0, tank.shieldTicks() - 1),
                activateShield
                        ? rules.shieldCooldownTicks()
                        : Math.max(0, tank.shieldCooldownTicks() - 1));
    }

    /**
     * Stop participants moving toward a contact, while allowing a participant moving away from it
     * to continue. Resolve newly blocked chains without assigning priority by slot.
     */
    private void resolveTankContacts(List<Tank> previous, Tank[] moved) {
        boolean[] blocked = new boolean[moved.length];
        while (true) {
            boolean[] newlyBlocked = new boolean[moved.length];
            boolean changed = false;
            for (int first = 0; first < moved.length; first++) {
                if (previous.get(first).health() == 0) {
                    continue;
                }
                for (int second = first + 1; second < moved.length; second++) {
                    if (previous.get(second).health() == 0) {
                        continue;
                    }
                    Vec2 firstDelta =
                            moved[first].position().subtract(previous.get(first).position());
                    Vec2 secondDelta =
                            moved[second].position().subtract(previous.get(second).position());
                    double contact =
                            CollisionMath.circle(
                                    previous.get(first)
                                            .position()
                                            .subtract(previous.get(second).position()),
                                    firstDelta.subtract(secondDelta),
                                    2 * rules.tankRadius());
                    if (contact <= 1) {
                        Vec2 separation =
                                previous.get(first)
                                        .position()
                                        .subtract(previous.get(second).position());
                        double firstApproach =
                                separation.x() * firstDelta.x() + separation.y() * firstDelta.y();
                        double secondApproach =
                                separation.x() * secondDelta.x() + separation.y() * secondDelta.y();
                        if (firstApproach < 0) {
                            newlyBlocked[first] = true;
                        }
                        if (secondApproach > 0) {
                            newlyBlocked[second] = true;
                        }
                    }
                }
            }
            for (int slot = 0; slot < moved.length; slot++) {
                if (newlyBlocked[slot] && !blocked[slot]) {
                    Tank tank = moved[slot];
                    moved[slot] =
                            new Tank(
                                    slot,
                                    previous.get(slot).position(),
                                    new Vec2(0, 0),
                                    tank.heading(),
                                    tank.gunHeading(),
                                    tank.health(),
                                    tank.cooldownTicks(),
                                    tank.shieldTicks(),
                                    tank.shieldCooldownTicks());
                    blocked[slot] = true;
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    private void advanceProjectile(
            Projectile projectile,
            List<Tank> previous,
            Tank[] moved,
            boolean[] shielded,
            int[] damage,
            int[] attackers,
            List<GameEvent> events,
            List<Projectile> surviving,
            List<Supply> supplies,
            int tick) {
        Vec2 delta = projectile.velocity().scale(dt);
        double wallTime = obstacleContact(projectile.position(), delta, rules.bulletRadius());
        double hitTime = Double.POSITIVE_INFINITY;
        int target = -1;
        for (Tank candidate : previous) {
            if (candidate.health() == 0 || candidate.slot() == projectile.owner()) {
                continue;
            }
            Vec2 targetDelta = moved[candidate.slot()].position().subtract(candidate.position());
            double time =
                    CollisionMath.circle(
                            projectile.position().subtract(candidate.position()),
                            delta.subtract(targetDelta),
                            rules.tankRadius() + rules.bulletRadius());
            if (time <= 1
                    && (time < hitTime
                            || (time == hitTime
                                    && geometricallyBefore(
                                            candidate,
                                            previous.get(target),
                                            projectile.position(),
                                            delta,
                                            time)))) {
                target = candidate.slot();
                hitTime = time;
            }
        }
        double supplyTime = Double.POSITIVE_INFINITY;
        int supplyIndex = -1;
        for (int i = 0; i < supplies.size(); i++) {
            Supply supply = supplies.get(i);
            if (supply.health() == 0) {
                continue;
            }
            double time =
                    CollisionMath.circle(
                            projectile.position().subtract(supply.position()),
                            delta,
                            CombatState.SUPPLY_RADIUS + rules.bulletRadius());
            if (time <= 1 && time < supplyTime) {
                supplyTime = time;
                supplyIndex = i;
            }
        }
        if (supplyIndex >= 0 && supplyTime < wallTime && supplyTime < hitTime) {
            supplies.set(supplyIndex, supplies.get(supplyIndex).damaged(tick, rules.tickRate()));
            events.add(
                    new GameEvent(
                            EventType.SUPPLY_HIT,
                            projectile.owner(),
                            -1,
                            projectile.position().add(delta.scale(supplyTime))));
        } else if (wallTime <= 1 && wallTime <= hitTime) {
            events.add(
                    new GameEvent(
                            EventType.WALL,
                            projectile.owner(),
                            -1,
                            projectile.position().add(delta.scale(wallTime))));
        } else if (target >= 0) {
            boolean allied =
                    !protectedTeams.isEmpty()
                            && protectedTeams
                                    .get(target)
                                    .equals(protectedTeams.get(projectile.owner()));
            if (!shielded[target] && !allied) {
                damage[target]++;
                if (attackers[target] == -1) {
                    attackers[target] = projectile.owner();
                } else if (attackers[target] != projectile.owner()) {
                    attackers[target] = -2;
                }
            }
            events.add(
                    new GameEvent(
                            allied
                                    ? EventType.ALLY_BLOCK
                                    : shielded[target] ? EventType.SHIELD_BLOCK : EventType.HIT,
                            projectile.owner(),
                            target,
                            projectile.position().add(delta.scale(hitTime))));
        } else {
            surviving.add(
                    new Projectile(
                            projectile.id(),
                            projectile.owner(),
                            projectile.position().add(delta),
                            projectile.velocity()));
        }
    }

    /** Exact contact ties use geometry, never participant identity or iteration order. */
    private static boolean geometricallyBefore(
            Tank candidate, Tank current, Vec2 from, Vec2 delta, double time) {
        Vec2 point = from.add(delta.scale(time));
        int byDistance =
                Double.compare(
                        candidate.position().distance(point), current.position().distance(point));
        if (byDistance != 0) {
            return byDistance < 0;
        }
        int byX = Double.compare(candidate.position().x(), current.position().x());
        return byX != 0 ? byX < 0 : candidate.position().y() < current.position().y();
    }

    private double obstacleContact(Vec2 from, Vec2 delta, double radius) {
        double earliest = CollisionMath.boundary(from, delta, map.width(), map.height(), radius);
        for (Wall wall : map.walls()) {
            earliest = Math.min(earliest, CollisionMath.wall(from, delta, wall, radius));
        }
        return earliest;
    }

    private void validateSpawns() {
        double radius = rules.tankRadius();
        for (int slot = 0; slot < map.spawns().size(); slot++) {
            Vec2 position = map.spawns().get(slot).position();
            if (position.x() < radius
                    || position.x() > map.width() - radius
                    || position.y() < radius
                    || position.y() > map.height() - radius) {
                throw new IllegalArgumentException("Spawn does not fit tank inside map");
            }
            for (Wall wall : map.walls()) {
                if (CollisionMath.distanceToWall(position, wall) <= radius) {
                    throw new IllegalArgumentException("Spawn overlaps wall");
                }
            }
            for (int other = 0; other < slot; other++) {
                if (position.distance(map.spawns().get(other).position()) < 2 * radius) {
                    throw new IllegalArgumentException("Spawns overlap");
                }
            }
        }
    }

    private Outcome result(List<Tank> tanks, int tick) {
        return tick >= rules.maxTicks()
                        || tanks.stream().filter(tank -> tank.health() > 0).count() <= 1
                ? Outcome.FINISHED
                : Outcome.RUNNING;
    }

    private static Tank withCooldown(Tank tank, int cooldown) {
        return new Tank(
                tank.slot(),
                tank.position(),
                tank.velocity(),
                tank.heading(),
                tank.gunHeading(),
                tank.health(),
                cooldown,
                tank.shieldTicks(),
                tank.shieldCooldownTicks());
    }

    private static Tank withHealth(Tank tank, int health) {
        return new Tank(
                tank.slot(),
                tank.position(),
                health == 0 ? new Vec2(0, 0) : tank.velocity(),
                tank.heading(),
                tank.gunHeading(),
                health,
                tank.cooldownTicks(),
                health == 0 ? 0 : tank.shieldTicks(),
                health == 0 ? 0 : tank.shieldCooldownTicks());
    }

    private static Vec2 direction(double radians) {
        return new Vec2(Math.cos(radians), Math.sin(radians));
    }

    private static double normalize(double radians) {
        return Math.IEEEremainder(radians, Math.PI * 2);
    }

    private static double safeFraction(double contact, double length) {
        return Math.max(0, contact - 1e-7 / Math.max(1, length));
    }
}

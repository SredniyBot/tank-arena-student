package edu.arena.core.recon;

import edu.arena.core.engine.ArenaGeometry;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Cargo;
import edu.arena.core.model.DeliveryDepot;
import edu.arena.core.model.DeliveryEvent;
import edu.arena.core.model.DeliveryState;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Tank;
import edu.arena.core.model.Vec2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Team-owned destinations with neutral reusable sources. Resolves after combat, before respawn. */
final class ReconDelivery {
    private final GameRules rules;
    private final ArenaMap map;
    private final ReconRules mode;
    private final List<DeliveryDepot> depots;
    private final List<Vec2> sources;
    private final boolean controlZones;

    ReconDelivery(
            GameRules rules, ArenaMap map, ReconRules mode, int sourceCount, boolean controlZones) {
        this.controlZones = controlZones;
        this.rules = rules;
        this.map = map;
        this.mode = mode;
        var locations = new ArrayList<DeliveryDepot>();
        for (int team = 0; team < mode.teams(); team++) {
            Vec2 total = new Vec2(0, 0);
            for (int member = 0; member < mode.teamSize(); member++) {
                total = total.add(map.spawns().get(team * mode.teamSize() + member).position());
            }
            locations.add(new DeliveryDepot(team, total.scale(1.0 / mode.teamSize()), 64));
        }
        double x = map.width() / 2;
        double y = map.height() / 2;
        Vec2 north = new Vec2(x, y - map.height() * 5 / 32);
        Vec2 south = new Vec2(x, y + map.height() * 5 / 32);
        sources =
                switch (sourceCount) {
                    case 2 -> List.of(north, south);
                    case 4 ->
                            List.of(
                                    north,
                                    new Vec2(x + map.width() * 5 / 32, y),
                                    south,
                                    new Vec2(x - map.width() * 5 / 32, y));
                    default ->
                            throw new IllegalArgumentException("Recon needs two or four sources");
                };
        for (int index = 0; index < sources.size(); index++) {
            Vec2 point = sources.get(index);
            if (!ArenaGeometry.clearPath(map, point, point, rules.tankRadius())) {
                throw new IllegalArgumentException("Recon source overlaps a wall or map boundary");
            }
            locations.add(new DeliveryDepot(mode.teams() + index, point, controlZones ? 100 : 30));
        }
        depots = List.copyOf(locations);
    }

    List<Vec2> sources() {
        return sources;
    }

    List<DeliveryDepot> depots() {
        return depots;
    }

    DeliveryState initial() {
        var cargo = new ArrayList<Cargo>();
        if (!controlZones) {
            for (int index = 0; index < sources.size(); index++) {
                cargo.add(new Cargo(index, mode.teams() + index, 0, sources.get(index), -1, 0));
            }
        }
        return new DeliveryState(depots, cargo, List.of());
    }

    DeliveryState step(DeliveryState previous, GameState world, Set<Integer> active) {
        int tick = world.tick();
        var cargo = new ArrayList<Cargo>();
        var events = new ArrayList<DeliveryEvent>();
        var occupied = new HashSet<Integer>();
        var released = new HashSet<Integer>();
        for (Cargo item : previous.cargo()) {
            if (item.carrierSlot() < 0) {
                cargo.add(item);
                continue;
            }
            Tank carrier = world.tanks().get(item.carrierSlot());
            if (carrier.health() == 0 || !active.contains(carrier.slot())) {
                cargo.add(
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                item.destinationId(),
                                carrier.position(),
                                -1,
                                tick));
                events.add(
                        new DeliveryEvent(
                                tick, carrier.slot(), item.id(), DeliveryEvent.Kind.DROPPED));
                released.add(item.id());
                continue;
            }
            int team = mode.teamOf(carrier.slot());
            DeliveryDepot base = depots.get(team);
            if (carrier.position().distance(base.position()) <= base.radius()) {
                cargo.add(
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                0,
                                source(item.sourceId()),
                                -1,
                                Math.addExact(tick, mode.cargoRespawnTicks())));
                events.add(
                        new DeliveryEvent(
                                tick, carrier.slot(), item.id(), DeliveryEvent.Kind.DELIVERED));
            } else {
                cargo.add(
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                team,
                                carrier.position(),
                                carrier.slot(),
                                item.availableAtTick()));
                occupied.add(carrier.slot());
            }
        }
        cargo.sort(Comparator.comparingInt(Cargo::id));
        for (int index = 0; index < cargo.size(); index++) {
            Cargo item = cargo.get(index);
            if (item.carrierSlot() >= 0
                    || item.availableAtTick() > tick
                    || released.contains(item.id())) {
                continue;
            }
            Tank nearest = null;
            double distance = Double.POSITIVE_INFINITY;
            boolean tie = false;
            for (Tank tank : world.tanks()) {
                if (tank.health() == 0
                        || !active.contains(tank.slot())
                        || occupied.contains(tank.slot())) {
                    continue;
                }
                double candidate = tank.position().distance(item.position());
                if (candidate > rules.tankRadius() + 12
                        || !ArenaGeometry.clearPath(map, tank.position(), item.position(), 0)) {
                    continue;
                }
                if (candidate < distance - 1e-9) {
                    nearest = tank;
                    distance = candidate;
                    tie = false;
                } else if (Math.abs(candidate - distance) <= 1e-9) {
                    tie = true;
                }
            }
            if (nearest != null && !tie) {
                cargo.set(
                        index,
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                mode.teamOf(nearest.slot()),
                                nearest.position(),
                                nearest.slot(),
                                tick));
                occupied.add(nearest.slot());
                events.add(
                        new DeliveryEvent(
                                tick, nearest.slot(), item.id(), DeliveryEvent.Kind.PICKUP));
            }
        }
        return new DeliveryState(depots, cargo, events);
    }

    private Vec2 source(int sourceId) {
        int index = sourceId - mode.teams();
        if (index < 0 || index >= sources.size()) {
            throw new IllegalArgumentException("Unknown neutral source");
        }
        return sources.get(index);
    }
}

package edu.arena.core.engine;

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
import java.util.Objects;
import java.util.Set;

/**
 * Pure objective pass after combat and before respawn. Existing physics and controls are unchanged.
 */
public final class DeliveryEngine {
    private final ArenaMap map;
    private final GameRules rules;

    public DeliveryEngine(ArenaMap map, GameRules rules) {
        this.map = Objects.requireNonNull(map, "map");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public DeliveryState initialState() {
        if (map.width() < 640 || map.height() < 640) {
            throw new IllegalArgumentException(
                    "Delivery requires an arena at least 640 units wide and high");
        }
        double inset = 180;
        var depots =
                List.of(
                        new DeliveryDepot(0, new Vec2(inset, inset), 52),
                        new DeliveryDepot(1, new Vec2(map.width() - inset, inset), 52),
                        new DeliveryDepot(
                                2, new Vec2(map.width() - inset, map.height() - inset), 52),
                        new DeliveryDepot(3, new Vec2(inset, map.height() - inset), 52));
        var cargo = new ArrayList<Cargo>();
        for (DeliveryDepot depot : depots) {
            for (int item = 0; item < 2; item++) {
                int id = 2 * depot.id() + item;
                Vec2 point = sourcePosition(depot, id);
                if (!ArenaGeometry.clearPath(map, point, point, rules.tankRadius())) {
                    throw new IllegalArgumentException("Delivery source overlaps an obstacle");
                }
                cargo.add(new Cargo(id, depot.id(), (depot.id() + 2) % 4, point, -1, 0));
            }
        }
        return new DeliveryState(depots, cargo, List.of());
    }

    public DeliveryState step(
            DeliveryState previous, GameState combatState, Set<Integer> activeSlots) {
        if (previous == null) {
            return null;
        }
        Objects.requireNonNull(combatState, "combatState");
        activeSlots = Set.copyOf(activeSlots);
        var cargo = new ArrayList<Cargo>();
        var events = new ArrayList<DeliveryEvent>();
        var occupied = new HashSet<Integer>();
        var released = new HashSet<Integer>();
        int tick = combatState.tick();
        // Death and departure win over delivery. Existing carriers are resolved before new pickups.
        for (Cargo item : previous.cargo()) {
            if (item.carrierSlot() < 0) {
                cargo.add(item);
                continue;
            }
            Tank carrier = combatState.tanks().get(item.carrierSlot());
            if (carrier.health() == 0 || !activeSlots.contains(carrier.slot())) {
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
            DeliveryDepot destination = depot(previous, item.destinationId());
            if (carrier.position().distance(destination.position()) <= destination.radius()) {
                DeliveryDepot source = depot(previous, item.sourceId());
                cargo.add(
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                item.destinationId(),
                                sourcePosition(source, item.id()),
                                -1,
                                Math.addExact(tick, Math.multiplyExact(5, rules.tickRate()))));
                events.add(
                        new DeliveryEvent(
                                tick, carrier.slot(), item.id(), DeliveryEvent.Kind.DELIVERED));
            } else {
                cargo.add(
                        new Cargo(
                                item.id(),
                                item.sourceId(),
                                item.destinationId(),
                                carrier.position(),
                                carrier.slot(),
                                item.availableAtTick()));
                occupied.add(carrier.slot());
            }
        }
        // Sorting makes collection independent of deserialized list order. A true distance tie
        // waits.
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
            for (Tank tank : combatState.tanks()) {
                if (tank.health() <= 0
                        || !activeSlots.contains(tank.slot())
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
                                item.destinationId(),
                                nearest.position(),
                                nearest.slot(),
                                item.availableAtTick()));
                occupied.add(nearest.slot());
                events.add(
                        new DeliveryEvent(
                                tick, nearest.slot(), item.id(), DeliveryEvent.Kind.PICKUP));
            }
        }
        return new DeliveryState(previous.depots(), cargo, events);
    }

    /** Releases ownership at slot reuse boundaries, without manufacturing scoring transitions. */
    public static DeliveryState release(DeliveryState state, int slot, Vec2 position, int tick) {
        if (state == null || state.cargo().stream().noneMatch(item -> item.carrierSlot() == slot)) {
            return state;
        }
        return new DeliveryState(
                state.depots(),
                state.cargo().stream()
                        .map(
                                item ->
                                        item.carrierSlot() == slot
                                                ? new Cargo(
                                                        item.id(),
                                                        item.sourceId(),
                                                        item.destinationId(),
                                                        position,
                                                        -1,
                                                        tick)
                                                : item)
                        .toList(),
                state.events());
    }

    private static DeliveryDepot depot(DeliveryState state, int id) {
        return state.depots().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    private static Vec2 sourcePosition(DeliveryDepot depot, int cargoId) {
        return depot.position().add(new Vec2(cargoId % 2 == 0 ? -40 : 40, 0));
    }
}

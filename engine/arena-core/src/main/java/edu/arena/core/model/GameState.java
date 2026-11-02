package edu.arena.core.model;

import java.util.List;
import java.util.Objects;

/** Immutable completed tick. Dead tanks keep their slot; events belong to this tick only. */
public record GameState(
        int tick,
        List<Tank> tanks,
        List<Projectile> projectiles,
        List<GameEvent> events,
        Outcome outcome,
        List<Elimination> eliminations,
        double safeRadius,
        DeliveryState delivery,
        edu.arena.core.combat.CombatState combat) {
    public GameState {
        if (tick < 0 || !Double.isFinite(safeRadius) || safeRadius < 0) {
            throw new IllegalArgumentException("Invalid tick or zone radius");
        }
        tanks = List.copyOf(Objects.requireNonNull(tanks, "tanks"));
        projectiles = List.copyOf(Objects.requireNonNull(projectiles, "projectiles"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        eliminations = List.copyOf(Objects.requireNonNull(eliminations, "eliminations"));
        Objects.requireNonNull(outcome, "outcome");
        if (tanks.size() < 2 || tanks.size() > 64) {
            throw new IllegalArgumentException("Expected 2 to 64 tanks");
        }
        if (combat != null && combat.equipment().size() != tanks.size()) {
            throw new IllegalArgumentException("Equipment roster differs from tank roster");
        }
        boolean[] eliminated = new boolean[tanks.size()];
        for (int slot = 0; slot < tanks.size(); slot++) {
            if (tanks.get(slot).slot() != slot) {
                throw new IllegalArgumentException("Tanks must be ordered by contiguous slot");
            }
        }
        for (Elimination elimination : eliminations) {
            int slot = elimination.slot();
            if (slot >= tanks.size()
                    || elimination.actorSlot() >= tanks.size()
                    || eliminated[slot]
                    || elimination.tick() > tick
                    || tanks.get(slot).health() != 0) {
                throw new IllegalArgumentException("Inconsistent elimination history");
            }
            eliminated[slot] = true;
        }
        for (Tank tank : tanks) {
            if ((tank.health() == 0) != eliminated[tank.slot()]) {
                throw new IllegalArgumentException("Each dead tank requires one elimination");
            }
        }
        for (Projectile projectile : projectiles) {
            if (projectile.owner() >= tanks.size()) {
                throw new IllegalArgumentException("Projectile owner is outside the roster");
            }
        }
        for (GameEvent event : events) {
            if (event.actor() >= tanks.size() || event.target() >= tanks.size()) {
                throw new IllegalArgumentException("Event participant is outside the roster");
            }
        }
        if (delivery != null) {
            for (Cargo cargo : delivery.cargo()) {
                if (cargo.carrierSlot() >= tanks.size()) {
                    throw new IllegalArgumentException("Cargo carrier is outside the roster");
                }
            }
            for (DeliveryEvent event : delivery.events()) {
                if (event.slot() >= tanks.size() || event.tick() > tick) {
                    throw new IllegalArgumentException("Delivery event is outside the snapshot");
                }
            }
        }
    }

    public GameState(
            int tick,
            List<Tank> tanks,
            List<Projectile> projectiles,
            List<GameEvent> events,
            Outcome outcome,
            List<Elimination> eliminations,
            double safeRadius) {
        this(tick, tanks, projectiles, events, outcome, eliminations, safeRadius, null);
    }

    public GameState(
            int tick,
            List<Tank> tanks,
            List<Projectile> projectiles,
            List<GameEvent> events,
            Outcome outcome,
            List<Elimination> eliminations,
            double safeRadius,
            DeliveryState delivery) {
        this(tick, tanks, projectiles, events, outcome, eliminations, safeRadius, delivery, null);
    }

    public GameState withCombat(edu.arena.core.combat.CombatState value) {
        return new GameState(
                tick,
                tanks,
                projectiles,
                events,
                outcome,
                eliminations,
                safeRadius,
                delivery,
                value);
    }

    public GameState withDelivery(DeliveryState value) {
        return new GameState(
                tick, tanks, projectiles, events, outcome, eliminations, safeRadius, value, combat);
    }
}

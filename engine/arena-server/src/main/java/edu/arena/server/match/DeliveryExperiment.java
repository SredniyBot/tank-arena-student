package edu.arena.server.match;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.ControlTiming;
import edu.arena.core.bot.Observation;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.engine.DeliveryEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.DeliveryEvent;
import edu.arena.core.model.GameState;
import edu.arena.server.config.ServerConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server-only repeatable comparison of arbitrary bot factories. Uses the real live command mailbox
 * and configured response/action windows, but assumes every computation meets its network deadline.
 */
public final class DeliveryExperiment {
    public record Standing(
            int competitor,
            int slot,
            int deliveries,
            int kills,
            int deaths,
            int cargoDeaths,
            int firstDeliveryTick,
            long decisions,
            long carryingTicks) {}

    public record Result(
            int ticks,
            int periodTicks,
            long seed,
            int rotation,
            List<Standing> standings,
            long carriedTicks,
            long groundTicks,
            long waitingTicks,
            GameState finalState) {
        public Result {
            standings = List.copyOf(standings);
        }
    }

    private DeliveryExperiment() {}

    /**
     * One competitor per factory, fresh instance after each respawn. Rotations 0..N-1 place every
     * competitor in every initial slot with the same seed. No external process or server is
     * started.
     */
    public static Result run(
            ServerConfig config,
            List<Supplier<Bot>> factories,
            int seconds,
            int rotation,
            long seed) {
        Objects.requireNonNull(config, "config");
        factories = List.copyOf(factories);
        int players = factories.size();
        if (players < 2
                || players > 64
                || seconds < 1
                || seconds > 180
                || rotation < 0
                || rotation >= players) {
            throw new IllegalArgumentException(
                    "Expected 2..64 bots, 1..180 seconds and a valid slot rotation");
        }
        var rules = config.game().rules();
        var map = ArenaMap.coveredCommons(players);
        var combat = new ArenaEngine(rules, map);
        var objective = new DeliveryEngine(map, rules);
        var order = new ArrayList<Integer>();
        for (int i = 0; i < players; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(seed));
        Collections.rotate(order, rotation);
        Set<Integer> active = new HashSet<>(order);
        int period = config.game().controlPeriodTicks();
        var state = combat.emptyState().withDelivery(objective.initialState());
        var bots = new ArrayList<Bot>();
        var inputs = new ArrayList<ControlMailbox>();
        int[] respawnAt = new int[players];
        int[] deliveries = new int[players];
        int[] kills = new int[players];
        int[] deaths = new int[players];
        int[] cargoDeaths = new int[players];
        int[] firstDelivery = new int[players];
        java.util.Arrays.fill(firstDelivery, -1);
        long[] decisions = new long[players];
        long[] carrying = new long[players];
        for (int slot = 0; slot < players; slot++) {
            state = combat.respawn(state, slot, config.live().protectionTicks(rules.tickRate()));
            if (state.tanks().get(slot).health() == 0) {
                throw new IllegalArgumentException(
                        "Map has no clear initial spawn for every competitor");
            }
            bots.add(
                    Objects.requireNonNull(
                            factories.get(order.get(slot)).get(), "factory returned null"));
            inputs.add(new ControlMailbox(rules.tickRate(), period));
        }
        long carriedTicks = 0;
        long groundTicks = 0;
        long waitingTicks = 0;
        for (int tick = 0; tick < seconds * rules.tickRate(); tick++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Experiment interrupted");
            }
            if (tick % period == 0) {
                var timing = new ControlTiming(tick, tick + period, tick + 2 * period, period, 0);
                for (int slot = 0; slot < players; slot++) {
                    if (state.tanks().get(slot).health() > 0) {
                        var command =
                                Objects.requireNonNull(
                                        bots.get(slot)
                                                .decide(
                                                        new Observation(
                                                                slot, state, map, rules, timing)),
                                        "Bot returned null");
                        inputs.get(slot).submit(tick, tick, command);
                        decisions[slot]++;
                    }
                }
            }
            var controls = new ArrayList<Control>();
            for (int slot = 0; slot < players; slot++) {
                Control command =
                        state.tanks().get(slot).health() > 0
                                ? inputs.get(slot).current(tick)
                                : null;
                controls.add(
                        command == null
                                ? Control.idle(state.tanks().get(slot).position())
                                : command);
            }
            var previous = state;
            state = combat.stepContinuous(state, controls);
            state = state.withDelivery(objective.step(previous.delivery(), state, active));
            for (var event : state.delivery().events()) {
                if (event.kind() == DeliveryEvent.Kind.DELIVERED) {
                    deliveries[event.slot()]++;
                    if (firstDelivery[event.slot()] < 0) {
                        firstDelivery[event.slot()] = state.tick();
                    }
                } else if (event.kind() == DeliveryEvent.Kind.DROPPED) {
                    cargoDeaths[event.slot()]++;
                }
            }
            for (var elimination : state.eliminations()) {
                int slot = elimination.slot();
                if (previous.tanks().get(slot).health() <= 0) {
                    continue;
                }
                deaths[slot]++;
                if (elimination.actorSlot() >= 0 && elimination.reason().equals("DESTROYED")) {
                    kills[elimination.actorSlot()]++;
                }
                respawnAt[slot] = state.tick() + config.live().respawnTicks(rules.tickRate());
                inputs.get(slot).reset(state.tick());
            }
            for (var cargo : state.delivery().cargo()) {
                if (cargo.carrierSlot() >= 0) {
                    carriedTicks++;
                    carrying[cargo.carrierSlot()]++;
                } else if (cargo.availableAtTick() > state.tick()) {
                    waitingTicks++;
                } else {
                    groundTicks++;
                }
            }
            for (int slot = 0; slot < players; slot++) {
                if (state.tanks().get(slot).health() <= 0 && state.tick() >= respawnAt[slot]) {
                    state =
                            combat.respawn(
                                    state, slot, config.live().protectionTicks(rules.tickRate()));
                    if (state.tanks().get(slot).health() > 0) {
                        bots.set(
                                slot,
                                Objects.requireNonNull(
                                        factories.get(order.get(slot)).get(),
                                        "factory returned null"));
                        inputs.get(slot).reset(state.tick());
                    }
                }
            }
        }
        var standings = new ArrayList<Standing>();
        for (int competitor = 0; competitor < players; competitor++) {
            int slot = order.indexOf(competitor);
            standings.add(
                    new Standing(
                            competitor,
                            slot,
                            deliveries[slot],
                            kills[slot],
                            deaths[slot],
                            cargoDeaths[slot],
                            firstDelivery[slot],
                            decisions[slot],
                            carrying[slot]));
        }
        return new Result(
                state.tick(),
                period,
                seed,
                rotation,
                standings,
                carriedTicks,
                groundTicks,
                waitingTicks,
                state);
    }
}

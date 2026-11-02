package edu.arena.client;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.Observation;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.EventType;
import edu.arena.core.model.GameRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Bounded offline experiment using the live arena's default physics, without network scheduling.
 */
public final class LocalPractice {
    private LocalPractice() {}

    /** Competitor zero is the student's bot. Slot is only its position in this experiment. */
    public record Standing(int competitor, int slot, int kills, int deaths) {}

    public record Result(
            int ticks,
            int candidateSlot,
            List<Standing> standings,
            int respawns,
            int blockedShots) {
        public Result {
            standings = List.copyOf(standings);
        }
    }

    /** Runs one fixed-time experiment; factories must create a fresh, nonblocking bot per life. */
    public static Result run(
            Supplier<Bot> candidate, Supplier<Bot> opponent, int players, int seconds, long seed) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(opponent, "opponent");
        if (players < 2 || players > 64) {
            throw new IllegalArgumentException("--players: от 2 до 64.");
        }
        if (seconds < 1 || seconds > 180) {
            throw new IllegalArgumentException("--seconds: от 1 до 180 игровых секунд.");
        }
        var rules = GameRules.standard(20, seconds);
        var map = ArenaMap.coveredCommons(players);
        var engine = new ArenaEngine(rules, map);
        var order = new ArrayList<Integer>();
        for (int i = 0; i < players; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(seed));
        var factories = new ArrayList<Supplier<Bot>>();
        var bots = new ArrayList<Bot>();
        var state = engine.emptyState();
        int respawnTicks = 2 * rules.tickRate();
        int protectionTicks = 3 * rules.tickRate() / 2;
        for (int slot = 0; slot < players; slot++) {
            var factory = order.get(slot) == 0 ? candidate : opponent;
            factories.add(factory);
            state = engine.respawn(state, slot, protectionTicks);
            if (state.tanks().get(slot).health() <= 0) {
                throw new IllegalStateException(
                        "Нет свободного начального места для локальной тренировки");
            }
            bots.add(create(factory, slot, 0));
        }
        int[] kills = new int[players];
        int[] deaths = new int[players];
        int[] respawnAt = new int[players];
        int respawns = 0;
        int blockedShots = 0;
        for (int tick = 0; tick < seconds * rules.tickRate(); tick++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Локальная тренировка прервана");
            }
            var previous = state;
            var controls = new ArrayList<Control>();
            for (int slot = 0; slot < players; slot++) {
                var view = new Observation(slot, state, map, rules);
                controls.add(
                        view.me().health() > 0
                                ? decide(bots.get(slot), view)
                                : Control.idle(view.me().position()));
            }
            state = engine.stepContinuous(state, controls);
            blockedShots +=
                    (int) state.events().stream().filter(e -> e.type() == EventType.WALL).count();
            for (var elimination : state.eliminations()) {
                int slot = elimination.slot();
                if (previous.tanks().get(slot).health() <= 0) {
                    continue;
                }
                deaths[slot]++;
                if (elimination.reason().equals("DESTROYED") && elimination.actorSlot() >= 0) {
                    kills[elimination.actorSlot()]++;
                }
                respawnAt[slot] = state.tick() + respawnTicks;
            }
            for (int slot = 0; slot < players; slot++) {
                if (state.tanks().get(slot).health() > 0 || state.tick() < respawnAt[slot]) {
                    continue;
                }
                state = engine.respawn(state, slot, protectionTicks);
                if (state.tanks().get(slot).health() > 0) {
                    bots.set(slot, create(factories.get(slot), slot, state.tick()));
                    respawns++;
                }
            }
        }
        var standings = new ArrayList<Standing>();
        for (int slot = 0; slot < players; slot++) {
            standings.add(new Standing(order.get(slot), slot, kills[slot], deaths[slot]));
        }
        return new Result(state.tick(), order.indexOf(0), standings, respawns, blockedShots);
    }

    private static Bot create(Supplier<Bot> factory, int slot, int tick) {
        try {
            return Objects.requireNonNull(factory.get(), "BotProvider.create вернул null");
        } catch (RuntimeException error) {
            throw failure(slot, tick, error);
        }
    }

    private static Control decide(Bot bot, Observation view) {
        try {
            var control = Objects.requireNonNull(bot.decide(view), "Bot.decide вернул null");
            if (Math.abs(control.aim().x()) > view.map().width() * 4
                    || Math.abs(control.aim().y()) > view.map().height() * 4) {
                throw new IllegalArgumentException("точка прицела слишком далеко от арены");
            }
            return control;
        } catch (RuntimeException error) {
            throw failure(view.self(), view.state().tick(), error);
        }
    }

    private static IllegalArgumentException failure(int slot, int tick, RuntimeException error) {
        return new IllegalArgumentException(
                "Ошибка стратегии: место " + slot + ", шаг " + tick + ": " + error.getMessage(),
                error);
    }
}

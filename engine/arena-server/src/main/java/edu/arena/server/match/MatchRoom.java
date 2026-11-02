package edu.arena.server.match;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.ControlTiming;
import edu.arena.core.bot.Observation;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Outcome;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.MatchPlayer;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.RecordedTurn;
import edu.arena.protocol.Messages.ReplayPage;
import edu.arena.server.config.ServerConfig.Game;
import edu.arena.server.persistence.CompletedMatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One shared round. The coordinator serializes access; every input window has a fixed end. */
final class MatchRoom {
    private final String id;
    private final String tournamentId;
    private final List<MatchPlayer> players;
    private final ArenaMap map;
    private final GameRules rules;
    private final Game config;
    private final ArenaEngine engine;
    private final Map<Integer, Bot> bots;
    private final List<GameState> frames = new ArrayList<>();
    private final List<RecordedTurn> turns = new ArrayList<>();
    private final Control[] pending;
    private final ControlMailbox[] inputs;
    private final int[] missing;
    private final Map<Integer, String> pendingExclusions = new HashMap<>();
    private final long periodMillis;
    private GameState state;
    private MatchView cachedView;
    private MatchView decisionSnapshot;
    private String status = "RUNNING";
    private String reason = "";
    private final SimulationPacer pacing;

    MatchRoom(
            String id,
            List<MatchPlayer> players,
            ArenaMap map,
            GameRules rules,
            Game config,
            Map<Integer, Bot> bots,
            String tournamentId,
            Set<String> unavailable,
            long nowNanos,
            long nowMillis) {
        this.id = id;
        this.players = List.copyOf(players);
        this.map = map;
        this.rules = rules;
        this.config = config;
        this.bots = Map.copyOf(bots);
        this.tournamentId = tournamentId;
        pending = new Control[players.size()];
        inputs = new ControlMailbox[players.size()];
        Arrays.setAll(
                inputs, slot -> new ControlMailbox(rules.tickRate(), config.controlPeriodTicks()));
        missing = new int[players.size()];
        periodMillis =
                Math.max(
                        (1000L + config.maxStepRate() - 1) / config.maxStepRate(),
                        config.deadlineMillis());
        pacing =
                new SimulationPacer(
                        periodMillis,
                        nowNanos,
                        nowMillis,
                        Math.max(periodMillis, config.startupGraceMillis()));
        engine = new ArenaEngine(rules, map);
        state = engine.initialState();
        var excluded = new HashSet<Integer>();
        players.stream()
                .filter(p -> unavailable.contains(p.id()))
                .forEach(p -> excluded.add(p.slot()));
        if (!excluded.isEmpty()) {
            state = engine.eliminate(state, excluded, "REVOKED");
        }
        frames.add(state);
        if (state.outcome() == Outcome.FINISHED) {
            finish("REVOKED");
        } else {
            openTurn(nowNanos, nowMillis);
        }
    }

    MatchView observation() {
        return status.equals("RUNNING") ? decisionSnapshot : view();
    }

    MatchView view() {
        if (cachedView == null) {
            cachedView = view(status);
        }
        return cachedView;
    }

    private MatchView view(String viewStatus) {
        return new MatchView(
                id,
                viewStatus,
                map,
                rules,
                state,
                players,
                status.equals("RUNNING") ? pacing.deadlineMillis() : 0,
                reason,
                !tournamentId.isEmpty(),
                Arrays.stream(missing).boxed().toList(),
                tournamentId,
                engine.placements(state));
    }

    boolean contains(String playerId) {
        return players.stream().anyMatch(p -> !p.bot() && p.id().equals(playerId));
    }

    CommandReceipt submit(String playerId, int tick, Control control) {
        int slot = slot(playerId);
        if (control == null) {
            throw new ApplicationFailure(400, "INVALID_COMMAND", "Команда отсутствует.");
        }
        if (Math.abs(control.aim().x()) > map.width() * 4
                || Math.abs(control.aim().y()) > map.height() * 4) {
            throw new ApplicationFailure(
                    400, "INVALID_AIM", "Точка прицеливания слишком далеко от арены.");
        }
        if (!status.equals("RUNNING")) {
            throw new ApplicationFailure(409, "MATCH_FINISHED", "Раунд уже завершён.");
        }
        if (state.tanks().get(slot).health() == 0 || pendingExclusions.containsKey(slot)) {
            throw new ApplicationFailure(409, "ELIMINATED", "Ваш танк выбыл из раунда.");
        }
        return inputs[slot].submit(tick, state.tick(), control);
    }

    void advance(long nowNanos, long nowMillis) {
        if (!status.equals("RUNNING") || !pacing.due(nowNanos)) {
            return;
        }
        cachedView = null;
        var absent = new ArrayList<Boolean>();
        var excluded = new HashMap<>(pendingExclusions);
        for (int slot = 0; slot < players.size(); slot++) {
            boolean alive = state.tanks().get(slot).health() > 0 && !excluded.containsKey(slot);
            pending[slot] = alive ? inputs[slot].current(state.tick()) : null;
            boolean missed = alive && pending[slot] == null;
            absent.add(missed);
            if (missed && state.tick() >= config.controlPeriodTicks()) {
                missing[slot]++;
            } else if (alive) {
                missing[slot] = 0;
            }
            if (!alive || pending[slot] == null) {
                pending[slot] = Control.idle(state.tanks().get(slot).position());
            }
            if (alive && missing[slot] >= config.missedLimit()) {
                excluded.put(slot, "COMMAND_TIMEOUT");
            }
        }
        for (int slot : excluded.keySet()) {
            pending[slot] = Control.idle(state.tanks().get(slot).position());
        }
        turns.add(new RecordedTurn(state.tick(), List.copyOf(Arrays.asList(pending)), absent));
        state = engine.step(state, Arrays.asList(pending), excluded);
        pacing.advanced(nowNanos, nowMillis);
        pendingExclusions.clear();
        frames.add(state);
        if (state.outcome() == Outcome.FINISHED) {
            var reasons = new HashSet<>(excluded.values());
            finish(
                    state.tick() >= rules.maxTicks()
                            ? "TIME_LIMIT"
                            : reasons.size() == 1 ? reasons.iterator().next() : "ELIMINATION");
        } else {
            openTurn(nowNanos, nowMillis);
        }
    }

    void forfeit(String playerId) {
        eliminate(playerId, "FORFEIT");
    }

    void revoke(String playerId) {
        eliminate(playerId, "REVOKED");
    }

    void kick(String playerId) {
        eliminate(playerId, "ADMIN_KICK");
    }

    private void eliminate(String playerId, String why) {
        int slot = slot(playerId);
        if (!status.equals("RUNNING") || state.tanks().get(slot).health() == 0) {
            return;
        }
        if (why.equals("REVOKED")) {
            pendingExclusions.put(slot, why);
        } else {
            pendingExclusions.putIfAbsent(slot, why);
        }
    }

    void cancel() {
        if (status.equals("RUNNING")) {
            status = "CANCELLED";
            reason = "ADMIN_CANCELLED";
            cachedView = null;
        }
    }

    void failed(String failure) {
        status = "FAILED";
        reason = failure;
        cachedView = null;
    }

    CompletedMatch completed() {
        return new CompletedMatch(view("FINISHED"), frames, turns);
    }

    ReplayPage replay(int from, int limit) {
        return page(view(), frames, turns, from, limit);
    }

    static ReplayPage page(
            MatchView view, List<GameState> frames, List<RecordedTurn> turns, int from, int limit) {
        if (from < 0 || from > frames.size() || limit < 1 || limit > 200) {
            throw new ApplicationFailure(
                    400, "INVALID_PAGE", "Страница повтора вне допустимого диапазона.");
        }
        int end = Math.min(frames.size(), from + limit);
        return new ReplayPage(
                view,
                from,
                frames.size(),
                List.copyOf(frames.subList(from, end)),
                turns.stream().filter(t -> t.tick() >= from && t.tick() < end).toList());
    }

    private int slot(String playerId) {
        return players.stream()
                .filter(p -> !p.bot() && p.id().equals(playerId))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ApplicationFailure(
                                        403, "NOT_PARTICIPANT", "Вы не участник этого матча."))
                .slot();
    }

    private void openTurn(long nowNanos, long nowMillis) {
        Arrays.fill(pending, null);
        int period = config.controlPeriodTicks();
        if (state.tick() % period == 0) {
            decisionSnapshot = view().withControlTiming(controlTiming(state.tick()));
            bots.forEach(
                    (slot, bot) -> {
                        if (state.tanks().get(slot).health() > 0) {
                            inputs[slot].submit(
                                    state.tick(),
                                    state.tick(),
                                    bot.decide(
                                            new Observation(
                                                    slot,
                                                    state,
                                                    map,
                                                    rules,
                                                    decisionSnapshot.controlTiming())));
                        }
                    });
        } else if (decisionSnapshot != null && pacing.resynced()) {
            var timing = controlTiming(decisionSnapshot.state().tick());
            if (timing.responseDeadlineEpochMillis()
                    != decisionSnapshot.controlTiming().responseDeadlineEpochMillis()) {
                decisionSnapshot = decisionSnapshot.withControlTiming(timing);
            }
        }
    }

    private ControlTiming controlTiming(int observed) {
        int period = config.controlPeriodTicks();
        return new ControlTiming(
                observed,
                observed + period,
                observed + 2 * period,
                period,
                pacing.deadlineMillis() + (period - 1L - state.tick() % period) * periodMillis);
    }

    Map<String, Object> timingMetrics() {
        return pacing.metrics(inputs, rules.tickRate());
    }

    private void finish(String why) {
        status = "SAVING";
        reason = why;
    }
}

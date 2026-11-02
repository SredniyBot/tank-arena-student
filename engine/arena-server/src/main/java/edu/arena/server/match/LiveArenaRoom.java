package edu.arena.server.match;

import edu.arena.core.bot.ControlTiming;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.engine.DeliveryEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.DeliveryEvent;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.recon.ReconEngine;
import edu.arena.core.recon.ReconMaps;
import edu.arena.core.recon.ReconObjective;
import edu.arena.core.recon.ReconRules;
import edu.arena.core.recon.ReconState;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.GameConditions;
import edu.arena.protocol.Messages.LiveEvent;
import edu.arena.protocol.Messages.LivePlayer;
import edu.arena.protocol.Messages.LiveView;
import edu.arena.protocol.Messages.MatchPlayer;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.ReconFrame;
import edu.arena.protocol.Messages.ReconView;
import edu.arena.protocol.Messages.RecordedTurn;
import edu.arena.protocol.Messages.ReplayPage;
import edu.arena.protocol.Messages.RulesControl;
import edu.arena.protocol.Messages.RulesRevision;
import edu.arena.server.config.ServerConfig;
import edu.arena.server.security.PlayerIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Endless shared field with stable occupied slots; coordinator serializes every mutation. */
final class LiveArenaRoom {
    private final String id = UUID.randomUUID().toString();
    private final ArenaMap map;
    private GameRules rules;
    private final ServerConfig config;
    private ArenaEngine engine;
    private DeliveryEngine deliveryEngine;
    private ReconEngine reconEngine;
    private ReconState reconState;
    private ReconState decisionReconState;
    private final Map<String, Integer> preferredTeams = new HashMap<>();
    private final LiveScores scores;
    private final PlayerIdentity[] players;
    private final Control[] pending;
    private final ControlMailbox[] inputs;
    private final int[] missing;
    private final int[] respawnAt;
    private final long[] lastSeen;
    private final long[] life;
    private final Map<Integer, String> exits = new HashMap<>();
    private final Map<String, PlayerIdentity> renames = new HashMap<>();
    private final LinkedHashMap<String, PlayerIdentity> joining = new LinkedHashMap<>();
    private final Map<String, Long> queuedSeen = new HashMap<>();
    // Identity count bounds this map; expired entries are removed at every simulation boundary.
    private final Map<String, Integer> reentryAt = new HashMap<>();
    private final ArrayDeque<GameState> frames = new ArrayDeque<>();
    private final ArrayDeque<RecordedTurn> turns = new ArrayDeque<>();
    private final ArrayDeque<LiveEvent> events = new ArrayDeque<>();
    private final ArrayDeque<RulesRevision> ruleChanges = new ArrayDeque<>();
    private final GameConditions defaults;
    private GameConditions conditions;
    private GameConditions pendingConditions;
    private long rulesRevision;
    private long appliedRulesRevision;
    private int rulesAppliedAtTick;
    private int respawnTicks;
    private GameState state;
    private MatchView cached;
    private MatchView decisionSnapshot;
    private final SimulationPacer pacing;
    private long eventSequence;
    private long lifeSequence;
    private boolean healthy = true;
    private String roundId = UUID.randomUUID().toString();
    private edu.arena.protocol.Messages.RoundStartStatus pendingRound;
    private final LinkedHashMap<String, edu.arena.protocol.Messages.RoundStartStatus>
            roundRequests = new LinkedHashMap<>();

    LiveArenaRoom(ServerConfig config, LiveScores scores, long nowNanos, long nowMillis) {
        this.config = config;
        this.scores = scores;
        rules = config.game().rules();
        defaults = LiveRuleSettings.defaults(config);
        conditions = defaults;
        respawnTicks = config.live().respawnTicks(rules.tickRate());
        ruleChanges.add(new RulesRevision(0, 0, rules, respawnTicks));
        int n = config.capacity().participants();
        map =
                config.live().combatEnabled()
                        ? ReconMaps.combat(n)
                        : config.live().reconEnabled()
                                ? (config.live().controlZonesEnabled()
                                        ? ReconMaps.controlPoints(4, 3)
                                        : ReconMaps.crossroads(4, 3))
                                : ArenaMap.coveredCommons(n);
        engine = new ArenaEngine(rules, map);
        reconEngine =
                config.live().reconEnabled()
                        ? new ReconEngine(
                                rules,
                                map,
                                new ReconRules(
                                        config.live().combatEnabled() ? n : 4,
                                        config.live().combatEnabled() ? 1 : 3,
                                        config.live().visionRadius(),
                                        config.live().respawnTicks(rules.tickRate()),
                                        config.live().protectionTicks(rules.tickRate()),
                                        ReconRules.standard(rules.tickRate()).cargoRespawnTicks(),
                                        config.live().cargoSpeedPercent() / 100.0,
                                        n),
                                config.live().reconSourceCount(),
                                config.live().combatEnabled()
                                        ? ReconObjective.COMBAT
                                        : config.live().controlZonesEnabled()
                                                ? ReconObjective.CONTROL_ZONES
                                                : ReconObjective.DELIVERY)
                        : null;
        deliveryEngine =
                config.live().deliveryEnabled() && reconEngine == null
                        ? new DeliveryEngine(map, rules)
                        : null;
        players = new PlayerIdentity[n];
        pending = new Control[n];
        inputs = new ControlMailbox[n];
        Arrays.setAll(
                inputs,
                slot -> new ControlMailbox(rules.tickRate(), config.game().controlPeriodTicks()));
        missing = new int[n];
        respawnAt = new int[n];
        lastSeen = new long[n];
        life = new long[n];
        pacing =
                new SimulationPacer(
                        config.game().periodMillis(),
                        nowNanos,
                        nowMillis,
                        config.game().periodMillis());
        state = engine.emptyState();
        if (reconEngine != null) {
            reconState = reconEngine.emptyState();
            state = reconState.arena();
        }
        if (deliveryEngine != null) {
            state = state.withDelivery(deliveryEngine.initialState());
        }
        frames.add(state);
        open(nowNanos, nowMillis);
    }

    String id() {
        return id;
    }

    boolean contains(String playerId) {
        return slotOrMinusOne(playerId) >= 0;
    }

    boolean queued(String playerId) {
        return joining.containsKey(playerId);
    }

    int queuedCount() {
        return joining.size();
    }

    List<MatchPlayer> waiting() {
        var out = new ArrayList<MatchPlayer>();
        joining.values()
                .forEach(p -> out.add(new MatchPlayer(p.id(), p.name(), out.size(), false)));
        return List.copyOf(out);
    }

    void enqueue(PlayerIdentity player, long now) {
        int occupied = slotOrMinusOne(player.id());
        if (occupied >= 0 && !exits.containsKey(occupied)) {
            return;
        }
        if (!joining.containsKey(player.id())
                && joining.size() >= config.capacity().queuedPlayers()) {
            throw new ApplicationFailure(
                    503, "CAPACITY_REACHED", "Очередь заполнена. Повторите попытку позже.");
        }
        joining.put(player.id(), player);
        queuedSeen.put(player.id(), now);
    }

    void cancelQueue(String playerId) {
        joining.remove(playerId);
        queuedSeen.remove(playerId);
    }

    void touch(String playerId, long now) {
        int s = slotOrMinusOne(playerId);
        if (s >= 0) {
            lastSeen[s] = now;
        }
        if (joining.containsKey(playerId)) {
            queuedSeen.put(playerId, now);
        }
    }

    void rename(PlayerIdentity player) {
        if (joining.containsKey(player.id())) {
            joining.put(player.id(), player);
        }
        renames.put(player.id(), player);
    }

    void leave(String playerId, String reason) {
        joining.remove(playerId);
        queuedSeen.remove(playerId);
        int slot = slotOrMinusOne(playerId);
        if (slot >= 0) {
            exits.put(slot, reason);
            pending[slot] = null;
        }
    }

    void fail() {
        healthy = false;
        cached = null;
    }

    MatchView observation() {
        return healthy ? decisionSnapshot : view();
    }

    ReconFrame reconObservation(String playerId) {
        if (reconEngine == null) {
            throw new ApplicationFailure(
                    409, "RECON_DISABLED", "Этот сервер работает в обычном режиме.");
        }
        int self = slot(playerId);
        if (exits.containsKey(self)) {
            throw new ApplicationFailure(403, "NOT_PARTICIPANT", "Вы больше не участник арены.");
        }
        if (decisionSnapshot.players().stream()
                .noneMatch(p -> p.id().equals(playerId) && p.slot() == self)) {
            throw new ApplicationFailure(
                    409, "OBSERVATION_PENDING", "Ожидается общий такт наблюдения.");
        }
        return reconObservation(self);
    }

    ReconFrame reconObservation(int self) {
        if (reconEngine == null || self < 0 || self >= players.length) {
            throw new ApplicationFailure(
                    400, "INVALID_INPUT", "Укажите слот участника режима разведки.");
        }
        var observation =
                reconEngine.observe(
                        decisionReconState, self, decisionSnapshot.controlTiming(), roundId);
        return new ReconFrame(
                id,
                healthy ? "RUNNING" : "FAILED",
                decisionReconState.respawnAt().get(self),
                observation,
                decisionSnapshot.players());
    }

    edu.arena.protocol.Messages.RoundStartStatus scheduleRound(
            String requestId, String expectedRoundId) {
        if (reconEngine == null) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        if (!healthy) {
            throw new ApplicationFailure(409, "ARENA_FAILED", "Арена не работает.");
        }
        try {
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new ApplicationFailure(400, "INVALID_INPUT", "Нужен идентификатор запроса UUID.");
        }
        var previous = roundRequests.get(requestId);
        if (previous != null) {
            if (!previous.expectedRoundId().equals(expectedRoundId)) {
                throw new ApplicationFailure(
                        409,
                        "ROUND_REQUEST_CONFLICT",
                        "Идентификатор запроса уже использован с другими данными.");
            }
            return previous;
        }
        if (!roundId.equals(expectedRoundId)) {
            throw new ApplicationFailure(
                    409,
                    "ROUND_CHANGED",
                    "Бой уже сменился. Обновите состояние перед новым стартом.");
        }
        if (pendingRound != null) {
            throw new ApplicationFailure(409, "ROUND_PENDING", "Новый бой уже ожидает старта.");
        }
        int period = config.game().controlPeriodTicks();
        int target = Math.addExact(state.tick(), period - state.tick() % period);
        pendingRound =
                new edu.arena.protocol.Messages.RoundStartStatus(
                        requestId, roundId, UUID.randomUUID().toString(), target, false);
        roundRequests.put(requestId, pendingRound);
        while (roundRequests.size() > 64) {
            roundRequests.remove(roundRequests.keySet().iterator().next());
        }
        return pendingRound;
    }

    edu.arena.protocol.Messages.RoundStatus roundStatus() {
        return new edu.arena.protocol.Messages.RoundStatus(roundId, pendingRound);
    }

    edu.arena.protocol.Messages.RoundStartStatus roundStatus(String requestId) {
        var result = roundRequests.get(requestId);
        if (result == null) {
            throw new ApplicationFailure(404, "ROUND_NOT_FOUND", "Запрос не найден.");
        }
        return result;
    }

    ReconFrame reconPreparation() {
        if (reconEngine == null) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        var empty = reconEngine.emptyState();
        var world = empty.arena();
        var syntheticWorld =
                new GameState(
                        decisionSnapshot.controlTiming().observedTick(),
                        world.tanks(),
                        world.projectiles(),
                        world.events(),
                        world.outcome(),
                        world.eliminations(),
                        world.safeRadius(),
                        world.delivery(),
                        world.combat());
        var syntheticState =
                new ReconState(
                        syntheticWorld, empty.teamScores(), empty.respawnAt(), empty.lives());
        var synthetic =
                reconEngine.observe(syntheticState, 0, decisionSnapshot.controlTiming(), roundId);
        return new ReconFrame(id, healthy ? "RUNNING" : "FAILED", 0, synthetic, List.of());
    }

    void assignTeam(String playerId, int team) {
        if (config.live().combatEnabled()) {
            throw new ApplicationFailure(409, "INDEPENDENT_PLAYERS", "В этом режиме нет команд.");
        }
        if (reconEngine == null || team < 0 || team >= reconEngine.mode().teams()) {
            throw new ApplicationFailure(
                    400, "INVALID_TEAM", "Выберите существующую команду этой арены.");
        }
        if (contains(playerId)) {
            throw new ApplicationFailure(409, "PLAYER_BUSY", "Сначала выведите участника с поля.");
        }
        preferredTeams.put(playerId, team);
    }

    MatchView view() {
        if (cached != null) {
            return cached;
        }
        var roster = new ArrayList<MatchPlayer>();
        var live = new ArrayList<LivePlayer>();
        for (int slot = 0; slot < players.length; slot++) {
            var p = players[slot];
            if (p == null) {
                continue;
            }
            roster.add(new MatchPlayer(p.id(), p.name(), slot, false));
            var score = scores.get(p.id());
            live.add(
                    new LivePlayer(
                            p.id(),
                            p.name(),
                            slot,
                            score.kills(),
                            score.deaths(),
                            config.live().combatEnabled()
                                    ? 100L * (score.kills() - score.deaths())
                                    : deliveryEngine == null && reconEngine == null
                                            ? score.kills()
                                            : score.deliveries(),
                            respawnAt[slot],
                            life[slot]));
        }
        cached =
                new MatchView(
                        id,
                        healthy ? "RUNNING" : "FAILED",
                        map,
                        rules,
                        state,
                        roster,
                        pacing.deadlineMillis(),
                        healthy ? "" : "LIVE_STORAGE_ERROR",
                        false,
                        Arrays.stream(missing).boxed().toList(),
                        "",
                        List.of(),
                        new LiveView(
                                live, List.copyOf(events), frames.getFirst().tick(), respawnTicks));
        if (reconEngine != null) {
            cached =
                    cached.withRecon(
                            new ReconView(
                                    reconEngine.mode().teams(),
                                    reconEngine.mode().teamSize(),
                                    reconEngine.mode().visionRadius(),
                                    reconState.teamScores(),
                                    reconEngine.mode().cargoSpeedFactor(),
                                    roundId,
                                    java.util.stream.IntStream.range(0, players.length)
                                            .map(reconEngine.mode()::teamOf)
                                            .boxed()
                                            .toList()));
        }
        return cached;
    }

    RulesControl rulesControl() {
        return new RulesControl(
                reconEngine == null || config.live().combatEnabled(),
                rulesRevision,
                appliedRulesRevision,
                rulesAppliedAtTick,
                conditions,
                pendingConditions,
                pendingConditions == null ? null : state.tick() + 1,
                defaults,
                LiveRuleSettings.limits(),
                rules.tickRate(),
                false);
    }

    RulesControl changeRules(long expectedRevision, GameConditions settings) {
        if (reconEngine != null && !config.live().combatEnabled()) {
            throw new ApplicationFailure(
                    409,
                    "RECON_RULES_LOCKED",
                    "В режиме разведки условия задаются до запуска; смена правил во время опыта отключена.");
        }
        if (expectedRevision != rulesRevision) {
            throw new ApplicationFailure(
                    409,
                    "RULES_CONFLICT",
                    "Условия уже изменены в другой вкладке. Обновите их перед сохранением.");
        }
        var normalized = LiveRuleSettings.normalize(settings, rules.tickRate());
        pendingConditions = normalized;
        rulesRevision++;
        return rulesControl();
    }

    RulesControl resetRules(long expectedRevision) {
        return changeRules(expectedRevision, defaults);
    }

    CommandReceipt submit(String playerId, int tick, Control control, long now) {
        int slot = slot(playerId);
        lastSeen[slot] = now;
        if (!healthy) {
            throw new ApplicationFailure(
                    503, "STORAGE_UNAVAILABLE", "Арена приостановлена: проверьте хранилище.");
        }
        if (control == null) {
            throw new ApplicationFailure(400, "INVALID_COMMAND", "Команда отсутствует.");
        }
        if (Math.abs(control.aim().x()) > map.width() * 4
                || Math.abs(control.aim().y()) > map.height() * 4) {
            throw new ApplicationFailure(
                    400, "INVALID_AIM", "Точка прицеливания слишком далеко от арены.");
        }
        if (exits.containsKey(slot)) {
            throw new ApplicationFailure(409, "NOT_IN_ARENA", "Вы выходите из арены.");
        }
        if (state.tanks().get(slot).health() == 0) {
            throw new ApplicationFailure(409, "RESPAWNING", "Танк скоро появится снова.");
        }
        return inputs[slot].submit(tick, state.tick(), control);
    }

    void advance(long now, LongSupplier nanoClock, LongSupplier epochClock) {
        if (!healthy || !pacing.due(now)) {
            return;
        }
        if (reconEngine != null) {
            advanceRecon(now, nanoClock, epochClock);
            return;
        }
        cached = null;
        var queued = joining.keySet().iterator();
        while (queued.hasNext()) {
            var playerId = queued.next();
            if (now - queuedSeen.get(playerId)
                    >= config.live().idleTimeoutSeconds() * 1_000_000_000L) {
                queued.remove();
                queuedSeen.remove(playerId);
            }
        }
        for (int slot = 0; slot < players.length; slot++) {
            if (players[slot] != null
                    && now - lastSeen[slot]
                            >= config.live().idleTimeoutSeconds() * 1_000_000_000L) {
                exits.putIfAbsent(slot, "COMMAND_TIMEOUT");
            }
        }
        var absent = new ArrayList<Boolean>();
        var controls = new ArrayList<Control>();
        for (int slot = 0; slot < players.length; slot++) {
            boolean alive =
                    players[slot] != null
                            && state.tanks().get(slot).health() > 0
                            && !exits.containsKey(slot);
            pending[slot] = alive ? inputs[slot].current(state.tick()) : null;
            boolean missed = alive && pending[slot] == null;
            absent.add(missed);
            if (missed) {
                missing[slot] =
                        missing[slot] == Integer.MAX_VALUE ? Integer.MAX_VALUE : missing[slot] + 1;
            } else {
                missing[slot] = 0;
            }
            controls.add(
                    alive && pending[slot] != null
                            ? pending[slot]
                            : Control.idle(state.tanks().get(slot).position()));
        }
        turns.addLast(new RecordedTurn(state.tick(), controls, absent));
        var old = state;
        state = engine.stepContinuous(state, controls, exits);
        if (deliveryEngine != null) {
            var activeSlots = new java.util.HashSet<Integer>();
            for (int slot = 0; slot < players.length; slot++) {
                if (players[slot] != null && !exits.containsKey(slot)) {
                    activeSlots.add(slot);
                }
            }
            state = state.withDelivery(deliveryEngine.step(state.delivery(), state, activeSlots));
            for (var event : state.delivery().events()) {
                if (event.kind() == DeliveryEvent.Kind.DELIVERED) {
                    var player = players[event.slot()];
                    scores.delivered(player.id());
                    addEvent("DELIVERED", player, null);
                }
            }
        }
        reentryAt.values().removeIf(tick -> tick <= state.tick());
        for (var elimination : state.eliminations()) {
            int slot = elimination.slot();
            var victim = players[slot];
            if (victim == null || old.tanks().get(slot).health() == 0) {
                continue;
            }
            var actor = elimination.actorSlot() >= 0 ? players[elimination.actorSlot()] : null;
            boolean combat =
                    elimination.reason().equals("DESTROYED") || elimination.reason().equals("ZONE");
            if (combat) {
                scores.death(
                        victim.id(),
                        elimination.reason().equals("DESTROYED")
                                        && actor != null
                                        && !exits.containsKey(elimination.actorSlot())
                                ? actor.id()
                                : null);
                respawnAt[slot] = Math.addExact(state.tick(), respawnTicks);
            }
            addEvent(elimination.reason(), victim, actor);
            inputs[slot].reset(state.tick());
        }
        boolean changedRoster = false;
        for (var entry : exits.entrySet()) {
            int slot = entry.getKey();
            if (players[slot] == null) {
                continue;
            }
            if (old.tanks().get(slot).health() == 0) {
                addEvent(entry.getValue(), players[slot], null);
            }
            // A voluntary disconnect cannot heal faster than a normal death. Administrative removal
            // is not a player ability: the teacher can still restore an account without this
            // penalty.
            if (entry.getValue().equals("LEFT") || entry.getValue().equals("COMMAND_TIMEOUT")) {
                reentryAt.put(
                        players[slot].id(),
                        Math.max(respawnAt[slot], Math.addExact(state.tick(), respawnTicks)));
            }
            state = engine.vacate(state, slot);
            players[slot] = null;
            respawnAt[slot] = 0;
            missing[slot] = 0;
            pending[slot] = null;
            inputs[slot].reset(state.tick());
            lastSeen[slot] = 0;
            life[slot] = 0;
            changedRoster = true;
        }
        exits.clear();
        for (var p : renames.values()) {
            int slot = slotOrMinusOne(p.id());
            if (slot >= 0) {
                players[slot] = p;
                changedRoster = true;
            }
            if (scores.get(p.id()) != null) {
                scores.register(p.id(), p.name());
            }
        }
        renames.clear();
        for (int slot = 0; slot < players.length; slot++) {
            if (players[slot] != null
                    && state.tanks().get(slot).health() == 0
                    && respawnAt[slot] > 0
                    && state.tick() >= respawnAt[slot]) {
                state =
                        engine.respawn(
                                state, slot, config.live().protectionTicks(rules.tickRate()));
                if (state.tanks().get(slot).health() > 0) {
                    respawnAt[slot] = 0;
                    inputs[slot].reset(state.tick());
                    life[slot] = ++lifeSequence;
                }
            }
        }
        for (int slot = 0; slot < players.length && !joining.isEmpty(); slot++) {
            if (players[slot] == null) {
                var first = joining.entrySet().iterator();
                PlayerIdentity p = null;
                while (first.hasNext()) {
                    var candidate = first.next().getValue();
                    if (reentryAt.getOrDefault(candidate.id(), 0) > state.tick()) {
                        continue;
                    }
                    p = candidate;
                    first.remove();
                    break;
                }
                if (p == null) {
                    break;
                }
                queuedSeen.remove(p.id());
                scores.register(p.id(), p.name());
                state =
                        engine.respawn(
                                state, slot, config.live().protectionTicks(rules.tickRate()));
                players[slot] = p;
                inputs[slot].reset(state.tick());
                lastSeen[slot] = now;
                if (state.tanks().get(slot).health() > 0) {
                    life[slot] = ++lifeSequence;
                } else {
                    respawnAt[slot] = state.tick() + 1;
                }
                changedRoster = true;
            }
        }
        if (changedRoster) {
            frames.clear();
            turns.clear();
        }
        applyPendingRules();
        frames.addLast(state);
        while (frames.size() > config.live().historyFrames()) {
            frames.removeFirst();
        }
        while (!turns.isEmpty() && turns.getFirst().tick() < frames.getFirst().tick()) {
            turns.removeFirst();
        }
        trimRuleHistory();
        Arrays.fill(pending, null);
        completeStep(nanoClock, epochClock);
    }

    private void advanceRecon(long now, LongSupplier nanoClock, LongSupplier epochClock) {
        cached = null;
        boolean changedRoster = false;
        var queued = joining.keySet().iterator();
        while (queued.hasNext()) {
            var playerId = queued.next();
            if (now - queuedSeen.get(playerId)
                    >= config.live().idleTimeoutSeconds() * 1_000_000_000L) {
                queued.remove();
                queuedSeen.remove(playerId);
            }
        }
        for (int slot = 0; slot < players.length; slot++) {
            if (players[slot] != null
                    && now - lastSeen[slot]
                            >= config.live().idleTimeoutSeconds() * 1_000_000_000L) {
                exits.putIfAbsent(slot, "COMMAND_TIMEOUT");
            }
        }
        var released = java.util.Set.copyOf(exits.keySet());
        for (var entry : exits.entrySet()) {
            int slot = entry.getKey();
            var player = players[slot];
            if (player == null) {
                continue;
            }
            addEvent(entry.getValue(), player, null);
            if (config.live().combatEnabled()
                    && state.tanks().get(slot).health() > 0
                    && (entry.getValue().equals("LEFT")
                            || entry.getValue().equals("COMMAND_TIMEOUT"))) {
                scores.death(player.id(), null);
                addEvent("WITHDRAWN", player, null);
            }
            if (entry.getValue().equals("LEFT") || entry.getValue().equals("COMMAND_TIMEOUT")) {
                reentryAt.put(player.id(), Math.max(respawnAt[slot], state.tick() + respawnTicks));
            }
            players[slot] = null;
            pending[slot] = null;
            inputs[slot].reset(state.tick());
            missing[slot] = 0;
            changedRoster = true;
        }
        exits.clear();
        for (var player : renames.values()) {
            int slot = slotOrMinusOne(player.id());
            if (slot >= 0) {
                players[slot] = player;
                changedRoster = true;
            }
            if (scores.get(player.id()) != null) {
                scores.register(player.id(), player.name());
            }
        }
        renames.clear();
        var applicants = joining.entrySet().iterator();
        while (applicants.hasNext()) {
            var player = applicants.next().getValue();
            if (reentryAt.getOrDefault(player.id(), 0) > state.tick()) {
                continue;
            }
            int slot = reconSlot(preferredTeams.get(player.id()), released);
            if (slot < 0) {
                continue;
            }
            applicants.remove();
            queuedSeen.remove(player.id());
            scores.register(player.id(), player.name());
            players[slot] = player;
            lastSeen[slot] = now;
            inputs[slot].reset(state.tick());
            changedRoster = true;
        }
        var active = new java.util.HashSet<Integer>();
        var controls = new ArrayList<Control>();
        var absent = new ArrayList<Boolean>();
        for (int slot = 0; slot < players.length; slot++) {
            if (players[slot] != null) {
                active.add(slot);
            }
            boolean alive = players[slot] != null && state.tanks().get(slot).health() > 0;
            Control command = alive ? inputs[slot].current(state.tick()) : null;
            boolean missed = alive && command == null;
            missing[slot] = missed ? Math.min(Integer.MAX_VALUE - 1, missing[slot]) + 1 : 0;
            absent.add(missed);
            controls.add(
                    command == null ? Control.idle(state.tanks().get(slot).position()) : command);
        }
        turns.addLast(new RecordedTurn(state.tick(), controls, absent));
        var old = reconState;
        reconState = reconEngine.step(reconState, controls, active);
        state = reconState.arena();
        for (var event :
                state.delivery() == null ? List.<DeliveryEvent>of() : state.delivery().events()) {
            var player = players[event.slot()];
            if (player != null && event.kind() == DeliveryEvent.Kind.DELIVERED) {
                scores.delivered(player.id());
                addEvent("DELIVERED", player, null);
            }
        }
        for (int slot = 0; slot < players.length; slot++) {
            respawnAt[slot] = reconState.respawnAt().get(slot);
            life[slot] = reconState.lives().get(slot);
            if (players[slot] != null
                    && old.arena().tanks().get(slot).health() > 0
                    && state.tanks().get(slot).health() == 0) {
                final int lost = slot;
                var elimination =
                        state.eliminations().stream()
                                .filter(e -> e.slot() == lost)
                                .findFirst()
                                .orElseThrow();
                var actor = elimination.actorSlot() >= 0 ? players[elimination.actorSlot()] : null;
                scores.death(players[slot].id(), actor == null ? null : actor.id());
                addEvent(elimination.reason(), players[slot], actor);
                inputs[slot].reset(state.tick());
            }
            if (!old.lives().get(slot).equals(reconState.lives().get(slot))) {
                inputs[slot].reset(state.tick());
            }
        }
        reentryAt.values().removeIf(tick -> tick <= state.tick());
        if (pendingRound != null && state.tick() == pendingRound.startTick()) {
            reconState = reconEngine.resetRound(reconState, active);
            state = reconState.arena();
            roundId = pendingRound.roundId();
            for (int slot = 0; slot < players.length; slot++) {
                inputs[slot].reset(state.tick());
                pending[slot] = null;
                missing[slot] = 0;
                respawnAt[slot] = reconState.respawnAt().get(slot);
                life[slot] = reconState.lives().get(slot);
            }
            frames.clear();
            turns.clear();
            events.clear();
            roundRequests.put(
                    pendingRound.requestId(),
                    new edu.arena.protocol.Messages.RoundStartStatus(
                            pendingRound.requestId(),
                            pendingRound.expectedRoundId(),
                            roundId,
                            state.tick(),
                            true));
            pendingRound = null;
        }
        if (changedRoster) {
            frames.clear();
            turns.clear();
        }
        if (config.live().combatEnabled()) {
            var personalScores = new ArrayList<Long>();
            for (int slot = 0; slot < players.length; slot++) {
                var score = players[slot] == null ? null : scores.get(players[slot].id());
                personalScores.add(score == null ? 0L : 100L * (score.kills() - score.deaths()));
            }
            state = state.withCombat(state.combat().withScores(personalScores));
            reconState = reconState.withArena(state);
            applyPendingRules();
        }
        frames.addLast(state);
        while (frames.size() > config.live().historyFrames()) {
            frames.removeFirst();
        }
        while (!turns.isEmpty() && turns.getFirst().tick() < frames.getFirst().tick()) {
            turns.removeFirst();
        }
        trimRuleHistory();
        completeStep(nanoClock, epochClock);
    }

    private void completeStep(LongSupplier nanoClock, LongSupplier epochClock) {
        // A slow step must not consume the next decision window before it is published.
        long completed = nanoClock.getAsLong();
        long completedMillis = epochClock.getAsLong();
        pacing.advanced(completed, completedMillis);
        open(completed, completedMillis);
    }

    private int reconSlot(Integer requestedTeam, java.util.Set<Integer> released) {
        int selected = -1;
        int minimum = Integer.MAX_VALUE;
        for (int team = 0; team < reconEngine.mode().teams(); team++) {
            if (requestedTeam != null && requestedTeam != team) {
                continue;
            }
            int count = 0;
            int available = -1;
            for (int offset = 0; offset < reconEngine.mode().sizeOfTeam(team); offset++) {
                int slot = reconEngine.mode().firstSlot(team) + offset;
                if (released.contains(slot)) {
                    continue;
                }
                if (players[slot] == null) {
                    if (available < 0) {
                        available = slot;
                    }
                } else {
                    count++;
                }
            }
            if (available >= 0 && count < minimum) {
                minimum = count;
                selected = available;
            }
        }
        return selected;
    }

    ReplayPage replay(int from, int limit) {
        if (from < 0 || from > state.tick() + 1 || limit < 1 || limit > 200) {
            throw new ApplicationFailure(
                    400, "INVALID_PAGE", "Страница повтора вне допустимого диапазона.");
        }
        int begin = Math.max(from, frames.getFirst().tick());
        int end = Math.min(state.tick() + 1, begin + limit);
        return new ReplayPage(
                view(),
                begin,
                state.tick() + 1,
                frames.stream().filter(s -> s.tick() >= begin && s.tick() < end).toList(),
                turns.stream().filter(t -> t.tick() >= begin && t.tick() < end).toList(),
                replayRules(begin, end));
    }

    void resume(long now, long millis) {
        for (int slot = 0; slot < players.length; slot++) {
            inputs[slot].reset(state.tick());
            lastSeen[slot] = now;
        }
        queuedSeen.replaceAll((player, seen) -> now);
        Arrays.fill(pending, null);
        cached = null;
        pacing.resume(now, millis);
        open(now, millis);
    }

    private void applyPendingRules() {
        if (pendingConditions == null) {
            return;
        }
        conditions = pendingConditions;
        pendingConditions = null;
        rules = LiveRuleSettings.rules(rules, conditions);
        engine = new ArenaEngine(rules, map);
        if (deliveryEngine != null) {
            deliveryEngine = new DeliveryEngine(map, rules);
        }
        respawnTicks = LiveRuleSettings.ticks(conditions.respawnMillis(), rules.tickRate());
        if (config.live().combatEnabled()) {
            var previousMode = reconEngine.mode();
            var nextMode =
                    new ReconRules(
                            previousMode.teams(),
                            previousMode.teamSize(),
                            previousMode.visionRadius(),
                            respawnTicks,
                            previousMode.spawnProtectionTicks(),
                            previousMode.cargoRespawnTicks(),
                            1,
                            previousMode.capacity());
            reconEngine = new ReconEngine(rules, map, nextMode, 4, ReconObjective.COMBAT);
        }
        appliedRulesRevision = rulesRevision;
        rulesAppliedAtTick = state.tick();
        ruleChanges.addLast(
                new RulesRevision(appliedRulesRevision, state.tick(), rules, respawnTicks));
    }

    private void trimRuleHistory() {
        int firstTick = frames.getFirst().tick();
        while (ruleChanges.size() > 1) {
            var iterator = ruleChanges.iterator();
            iterator.next();
            if (iterator.next().fromTick() > firstTick) {
                return;
            }
            ruleChanges.removeFirst();
        }
    }

    private List<RulesRevision> replayRules(int from, int end) {
        var result = new ArrayList<RulesRevision>();
        RulesRevision first = ruleChanges.getFirst();
        for (var revision : ruleChanges) {
            if (revision.fromTick() <= from) {
                first = revision;
            } else if (revision.fromTick() < end) {
                result.add(revision);
            }
        }
        result.addFirst(first);
        return List.copyOf(result);
    }

    private void addEvent(String reason, PlayerIdentity victim, PlayerIdentity actor) {
        events.addLast(
                new LiveEvent(
                        ++eventSequence,
                        state.tick(),
                        reason,
                        victim.id(),
                        victim.name(),
                        actor == null ? null : actor.id(),
                        actor == null ? null : actor.name()));
        while (events.size() > 64) {
            events.removeFirst();
        }
    }

    private int slotOrMinusOne(String id) {
        for (int slot = 0; slot < players.length; slot++) {
            if (players[slot] != null && players[slot].id().equals(id)) {
                return slot;
            }
        }
        return -1;
    }

    private int slot(String id) {
        int slot = slotOrMinusOne(id);
        if (slot < 0) {
            throw new ApplicationFailure(
                    403, "NOT_PARTICIPANT", "Вы не участник этой арены. Подключитесь снова.");
        }
        return slot;
    }

    private void open(long now, long millis) {
        int period = config.game().controlPeriodTicks();
        if (state.tick() % period == 0) {
            decisionSnapshot = view().withControlTiming(controlTiming(state.tick()));
            decisionReconState = reconState;
        } else if (reconEngine == null && decisionSnapshot != null && pacing.resynced()) {
            var timing = controlTiming(decisionSnapshot.state().tick());
            if (timing.responseDeadlineEpochMillis()
                    != decisionSnapshot.controlTiming().responseDeadlineEpochMillis()) {
                decisionSnapshot = decisionSnapshot.withControlTiming(timing);
            }
        }
    }

    private ControlTiming controlTiming(int observed) {
        int period = config.game().controlPeriodTicks();
        return new ControlTiming(
                observed,
                observed + period,
                observed + 2 * period,
                period,
                pacing.deadlineMillis()
                        + (period - 1L - state.tick() % period) * config.game().periodMillis());
    }

    Map<String, Object> timingMetrics() {
        return pacing.metrics(inputs, rules.tickRate());
    }
}

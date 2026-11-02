package edu.arena.server.match;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.HunterBot;
import edu.arena.core.bot.SentryBot;
import edu.arena.core.bot.StrafeBot;
import edu.arena.core.model.ArenaMap;
import edu.arena.protocol.Messages.AdminPlayer;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.GameConditions;
import edu.arena.protocol.Messages.LiveStanding;
import edu.arena.protocol.Messages.LobbyView;
import edu.arena.protocol.Messages.MatchPlayer;
import edu.arena.protocol.Messages.MatchSummary;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.Me;
import edu.arena.protocol.Messages.QueueReply;
import edu.arena.protocol.Messages.QueueRequest;
import edu.arena.protocol.Messages.ReplayPage;
import edu.arena.protocol.Messages.RulesControl;
import edu.arena.protocol.Messages.Standing;
import edu.arena.protocol.Messages.TournamentView;
import edu.arena.server.config.ServerConfig;
import edu.arena.server.persistence.CompletedMatch;
import edu.arena.server.persistence.MatchRepository;
import edu.arena.server.security.IdentityService;
import edu.arena.server.security.IdentityService.Presence;
import edu.arena.server.security.PlayerIdentity;
import edu.arena.server.tournament.TournamentService;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One live arena or finite official rounds, with bounded queues and durable score transitions. */
public final class MatchCoordinator implements AutoCloseable {
    private final ServerConfig config;
    private final MatchRepository repository;
    private final TournamentService tournaments;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<String, PlayerIdentity> waiting = new LinkedHashMap<>();
    private final LinkedHashMap<String, MatchView> terminal = new LinkedHashMap<>();
    private final Set<String> revokedPlayers = new HashSet<>();
    private final Set<String> reconWaiters = new HashSet<>();
    private final ScheduledExecutorService ticker;
    private final ThreadPoolExecutor writer;
    private MatchRoom current;
    private LiveArenaRoom live;
    private LiveArenaRoom pausedLive;
    private final LiveScores liveScores;
    private long scoreSavedRevision;
    private long scoreSaveDeadline;
    private boolean scoreSaving;

    private boolean storageHealthy = true;
    private boolean closed;
    private boolean saving;
    private long lobbyDeadlineNanos;
    private long lobbyStartsAtMillis;
    private long completedMatches;
    private long failedMatches;
    private long maxPulseNanos;

    public MatchCoordinator(
            ServerConfig config,
            MatchRepository repository,
            TournamentService tournaments,
            Clock clock,
            LongSupplier nanoClock) {
        this.config = config;
        this.repository = repository;
        this.tournaments = tournaments;
        this.clock = clock;
        this.nanoClock = nanoClock;
        ticker =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("arena-simulation").factory());
        writer =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(1),
                        Thread.ofPlatform().daemon(true).name("arena-storage").factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        if (config.live().enabled()) {
            try {
                liveScores =
                        new LiveScores(
                                config.dataDirectory().resolve("live-scores.json"),
                                config.capacity().players());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Cannot restore live scores", e);
            }
            live = new LiveArenaRoom(config, liveScores, nanoClock.getAsLong(), clock.millis());
            scoreSavedRevision = liveScores.snapshot().revision();
            scoreSaveDeadline =
                    nanoClock.getAsLong() + config.live().scoreSaveSeconds() * 1_000_000_000L;
            if (tournaments.running()) {
                pausedLive = live;
                live = null;
            }
        } else {
            live = null;
            liveScores = null;
        }
    }

    public void start() {
        ticker.scheduleWithFixedDelay(this::safePulse, 0, 5, TimeUnit.MILLISECONDS);
    }

    public synchronized RulesControl rulesControl() {
        if (live != null) {
            return live.rulesControl();
        }
        return new RulesControl(
                false,
                0,
                0,
                0,
                LiveRuleSettings.defaults(config),
                null,
                null,
                LiveRuleSettings.defaults(config),
                LiveRuleSettings.limits(),
                config.game().simulationHz(),
                false);
    }

    public synchronized RulesControl changeRules(long expectedRevision, GameConditions settings) {
        requireLiveRules();
        return live.changeRules(expectedRevision, settings);
    }

    public synchronized RulesControl resetRules(long expectedRevision) {
        requireLiveRules();
        return live.resetRules(expectedRevision);
    }

    private void requireLiveRules() {
        ensureAvailable();
        if (live == null) {
            throw new ApplicationFailure(
                    409,
                    "LIVE_RULES_ONLY",
                    "Игровые условия меняются только на непрерывной арене. Правила турнира фиксированы.");
        }
    }

    public synchronized Me me(PlayerIdentity player) {
        if (live != null) {
            live.touch(player.id(), nanoClock.getAsLong());
        }
        return new Me(
                player.id(),
                player.name(),
                activeMatch(player.id()).orElse(null),
                isQueued(player.id()),
                player.revoked() || revokedPlayers.contains(player.id()),
                player.approved());
    }

    public synchronized List<AdminPlayer> adminPlayers(List<Presence> presences) {
        return presences.stream()
                .map(
                        p ->
                                new AdminPlayer(
                                        p.player().id(),
                                        p.player().name(),
                                        p.player().revoked(),
                                        p.player().approved(),
                                        p.lastSeenEpochMillis(),
                                        p.online(),
                                        isQueued(p.player().id()),
                                        activeMatch(p.player().id()).orElse(null)))
                .toList();
    }

    /** Resolve current admission while holding the same lock used by revoke/restore. */
    public synchronized QueueReply enqueueOnline(Supplier<PlayerIdentity> currentIdentity) {
        var player = currentIdentity.get();
        if (activeMatch(player.id()).isPresent()) {
            throw new ApplicationFailure(
                    409,
                    "PLAYER_BUSY",
                    live == null
                            ? "Участник уже в раунде. Дождитесь его завершения."
                            : "Участник уже на арене. После уничтожения танк появится сам.");
        }
        return enqueue(player, new QueueRequest("JOIN", "", "commons"));
    }

    public synchronized LobbyView lobby() {
        if (live != null) {
            return new LobbyView(live.waiting(), live.id(), 0, config.capacity().participants(), 0);
        }
        var people = new ArrayList<MatchPlayer>();
        waiting.values()
                .forEach(p -> people.add(new MatchPlayer(p.id(), p.name(), people.size(), false)));
        return new LobbyView(
                people,
                current == null ? null : current.view().id(),
                lobbyStartsAtMillis,
                config.capacity().participants(),
                config.game().trainingBots());
    }

    public synchronized QueueReply enqueue(PlayerIdentity player, QueueRequest request) {
        ensureAvailable();
        ensurePlayer(player);
        if (request == null
                || !"JOIN".equals(request.mode())
                || (request.opponent() != null && !request.opponent().isEmpty())) {
            throw new ApplicationFailure(400, "INVALID_MODE", "Выберите вход в общую арену.");
        }
        validateMap(request.map());
        if (live != null) {
            live.enqueue(player, nanoClock.getAsLong());
            return new QueueReply(live.queued(player.id()), activeMatch(player.id()).orElse(null));
        }
        var existing = activeMatch(player.id());
        if (existing.isPresent()) {
            return new QueueReply(false, existing.get());
        }
        if (tournaments.contains(player.id())) {
            throw new ApplicationFailure(409, "IN_TOURNAMENT", "Вы участвуете в текущем турнире.");
        }
        if (!waiting.containsKey(player.id())
                && waiting.size() >= config.capacity().queuedPlayers()) {
            throw capacity();
        }
        boolean wasEmpty = waiting.isEmpty();
        waiting.putIfAbsent(player.id(), player);
        if (wasEmpty) {
            resetLobbyDeadline();
        }
        dispatch();
        return new QueueReply(isQueued(player.id()), activeMatch(player.id()).orElse(null));
    }

    /**
     * Resolve identity after request parsing, under the same lock used for rename and revocation.
     */
    public synchronized QueueReply enqueueCurrent(
            Supplier<PlayerIdentity> currentIdentity, QueueRequest request) {
        return enqueue(currentIdentity.get(), request);
    }

    public synchronized void cancelQueue(String playerId) {
        if (live != null) {
            live.cancelQueue(playerId);
            return;
        }
        waiting.remove(playerId);
        clearEmptyLobby();
    }

    public synchronized void revoke(String playerId, Runnable persistRevocation) {
        persistRevocation.run();
        revokedPlayers.add(playerId);
        if (pausedLive != null) {
            pausedLive.leave(playerId, "REVOKED");
        }
        if (live != null) {
            advanceLive(nanoClock.getAsLong());
            live.leave(playerId, "REVOKED");
            notifyAll();
            return;
        }
        waiting.remove(playerId);
        clearEmptyLobby();
        if (current != null && current.contains(playerId)) {
            current.advance(nanoClock.getAsLong(), clock.millis());
            current.revoke(playerId);
        }
    }

    /**
     * An exclusion ends at this round; it neither changes credentials nor the tournament roster.
     */
    public synchronized void kick(String playerId, Runnable requireIdentity) {
        requireIdentity.run();
        if (pausedLive != null) {
            pausedLive.leave(playerId, "ADMIN_KICK");
        }
        if (live != null) {
            advanceLive(nanoClock.getAsLong());
            live.leave(playerId, "ADMIN_KICK");
            notifyAll();
            return;
        }
        waiting.remove(playerId);
        clearEmptyLobby();
        if (current != null && current.contains(playerId)) {
            current.advance(nanoClock.getAsLong(), clock.millis());
            current.kick(playerId);
        }
    }

    public synchronized void restore(String playerId, Runnable persistRestoration) {
        persistRestoration.run();
        revokedPlayers.remove(playerId);
    }

    public synchronized CommandReceipt submit(String matchId, String playerId, Command command) {
        if (command == null) {
            throw new ApplicationFailure(400, "INVALID_COMMAND", "Команда отсутствует.");
        }
        if (live != null && live.id().equals(matchId)) {
            ensureAvailable();
            advanceLive(nanoClock.getAsLong());
            return live.submit(playerId, command.tick(), command.control(), nanoClock.getAsLong());
        }
        if (current == null || !current.view().id().equals(matchId)) {
            requireKnownParticipant(matchId, playerId);
            throw new ApplicationFailure(409, "MATCH_FINISHED", "Раунд уже завершён.");
        }
        var room = room(matchId);
        room.advance(nanoClock.getAsLong(), clock.millis());
        return room.submit(playerId, command.tick(), command.control());
    }

    public synchronized void forfeit(String matchId, String playerId) {
        if (live != null && live.id().equals(matchId)) {
            requireKnownParticipant(matchId, playerId);
            advanceLive(nanoClock.getAsLong());
            live.leave(playerId, "LEFT");
            notifyAll();
            return;
        }
        if (current == null || !current.view().id().equals(matchId)) {
            requireKnownParticipant(matchId, playerId);
            return;
        }
        current.advance(nanoClock.getAsLong(), clock.millis());
        current.forfeit(playerId);
    }

    public synchronized MatchView observation(String id) {
        if (live != null && live.id().equals(id)) {
            return live.observation();
        }
        if (current != null && current.view().id().equals(id)) {
            return current.observation();
        }
        return view(id);
    }

    public synchronized edu.arena.protocol.Messages.ReconFrame reconObservation(
            String id, String playerId) {
        if (live == null || !live.id().equals(id)) {
            throw notFound();
        }
        live.touch(playerId, nanoClock.getAsLong());
        return live.reconObservation(playerId);
    }

    /** Wait for the next shared decision snapshot without holding the coordinator lock. */
    public synchronized edu.arena.protocol.Messages.ReconFrame reconObservationAfter(
            String id, String playerId, int afterTick) {
        if (afterTick < 0) {
            throw new ApplicationFailure(400, "INVALID_INPUT", "Некорректный номер такта.");
        }
        var first = reconObservation(id, playerId);
        int current = first.observation().timing().observedTick();
        if (afterTick > current) {
            throw new ApplicationFailure(400, "INVALID_INPUT", "Такой такт ещё не наступил.");
        }
        if (afterTick < current || !first.status().equals("RUNNING")) {
            return first;
        }
        if (!reconWaiters.add(playerId)) {
            throw new ApplicationFailure(409, "WAIT_IN_PROGRESS", "Ожидание снимка уже запущено.");
        }
        try {
            long timeout =
                    Math.min(
                            1000L,
                            3L * config.game().controlPeriodTicks() * config.game().periodMillis());
            long end = System.nanoTime() + timeout * 1_000_000L;
            var frame = first;
            for (; ; ) {
                if (closed) {
                    throw new ApplicationFailure(503, "SERVER_STOPPING", "Арена останавливается.");
                }
                if (revokedPlayers.contains(playerId)) {
                    throw new ApplicationFailure(403, "REVOKED", "Доступ участника отозван.");
                }
                if (!frame.status().equals("RUNNING")
                        || frame.observation().timing().observedTick() > afterTick) {
                    return frame;
                }
                long remaining = end - System.nanoTime();
                if (remaining <= 0) {
                    return frame;
                }
                try {
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    wait(millis, nanos);
                    frame = reconObservation(id, playerId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApplicationFailure(503, "SERVER_STOPPING", "Ожидание прервано.");
                }
            }
        } finally {
            reconWaiters.remove(playerId);
        }
    }

    public synchronized edu.arena.protocol.Messages.ReconFrame reconPreparation(String playerId) {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        if (revokedPlayers.contains(playerId)) {
            throw new ApplicationFailure(403, "REVOKED", "Доступ отозван.");
        }
        return live.reconPreparation();
    }

    public synchronized edu.arena.protocol.Messages.ReconFrame reconObservation(
            String id, int slot) {
        if (live == null || !live.id().equals(id)) {
            throw notFound();
        }
        return live.reconObservation(slot);
    }

    public synchronized edu.arena.protocol.Messages.RoundStartStatus startReconRound(
            String requestId, String expectedRoundId) {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        advanceLive(nanoClock.getAsLong());
        return live.scheduleRound(requestId, expectedRoundId);
    }

    public synchronized edu.arena.protocol.Messages.RoundStatus reconRoundStatus() {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        return live.roundStatus();
    }

    public synchronized edu.arena.protocol.Messages.RoundStartStatus reconRoundStatus(
            String requestId) {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        return live.roundStatus(requestId);
    }

    public synchronized void assignReconTeam(PlayerIdentity player, int team) {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        live.assignTeam(player.id(), team);
    }

    public synchronized edu.arena.protocol.Messages.ReconView reconLeaderboard() {
        if (live == null || !config.live().reconEnabled()) {
            throw new ApplicationFailure(409, "RECON_DISABLED", "Режим разведки не включён.");
        }
        return live.view().recon();
    }

    public synchronized MatchView view(String id) {
        if (live != null && live.id().equals(id)) {
            return live.view();
        }
        if (current != null && current.view().id().equals(id)) {
            return current.view();
        }
        if (terminal.containsKey(id)) {
            return terminal.get(id);
        }
        return repository.view(id).orElseThrow(MatchCoordinator::notFound);
    }

    public synchronized List<MatchSummary> list() {
        var result = new LinkedHashMap<String, MatchView>();
        if (live != null) {
            result.put(live.id(), live.view());
        }
        if (current != null) {
            result.put(current.view().id(), current.view());
        }
        var recent = new ArrayList<>(terminal.values());
        Collections.reverse(recent);
        recent.forEach(v -> result.put(v.id(), v));
        repository.list().forEach(v -> result.putIfAbsent(v.id(), v));
        return result.values().stream()
                .limit(config.capacity().storedMatches() + 9L)
                .map(MatchSummary::of)
                .toList();
    }

    public ReplayPage replay(String id, int from, int limit) throws IOException {
        synchronized (this) {
            if (live != null && live.id().equals(id)) {
                return live.replay(from, limit);
            }
            if (current != null && current.view().id().equals(id)) {
                return current.replay(from, limit);
            }
        }
        return repository.replay(id, from, limit).orElseThrow(MatchCoordinator::notFound);
    }

    public synchronized TournamentView startTournament(
            List<PlayerIdentity> players, String map, int rounds, long seed) throws IOException {
        ensureAvailable();
        if (config.live().reconEnabled()) {
            throw new ApplicationFailure(
                    409,
                    "RECON_TOURNAMENT_UNAVAILABLE",
                    "В разведке работает непрерывная командная арена. Конечный турнир доступен в обычном профиле.");
        }
        validateMap(map);
        if (current != null && current.view().ranked()) {
            throw new ApplicationFailure(
                    409,
                    "ROUND_ACTIVE",
                    "Дождитесь завершения и сохранения текущего официального раунда.");
        }
        if (players == null
                || players.size() < 2
                || players.size()
                        > Math.min(
                                config.capacity().participants(),
                                config.capacity().tournamentPlayers())
                || players.stream().map(PlayerIdentity::id).distinct().count() != players.size()
                || rounds < 1
                || rounds > 32) {
            throw new ApplicationFailure(
                    400, "INVALID_ROSTER", "Недопустимый состав или число раундов.");
        }
        for (var p : players) {
            ensurePlayer(p);
            if (live == null && activeMatch(p.id()).isPresent()) {
                throw new ApplicationFailure(409, "PLAYER_BUSY", "Участник занят: " + p.name());
            }
        }
        tournaments.start(players, "commons", rounds, seed);
        if (live != null) {
            pausedLive = live;
            live = null;
        }
        players.forEach(p -> waiting.remove(p.id()));
        clearEmptyLobby();
        dispatch();
        return tournaments.view();
    }

    public synchronized void cancelTournament() throws IOException {
        tournaments.cancel();
        if (current != null
                && current.view().ranked()
                && current.view().status().equals("RUNNING")) {
            current.cancel();
            discardCurrent();
        }
        resumeLiveIfFinished();
    }

    public synchronized TournamentView tournament() {
        return tournaments.view();
    }

    public synchronized List<Standing> leaderboard() {
        return tournaments.standings();
    }

    public synchronized List<LiveStanding> liveLeaderboard() {
        return liveScores == null
                ? List.of()
                : liveScores.standings(
                        config.live().deliveryEnabled(), config.live().combatEnabled());
    }

    public synchronized PlayerIdentity rename(
            String playerId,
            String name,
            Supplier<PlayerIdentity> currentIdentity,
            Supplier<PlayerIdentity> persist) {
        String normalized = IdentityService.validatedName(name);
        var currentPlayer = currentIdentity.get();
        if (currentPlayer.name().equals(normalized)) {
            return currentPlayer;
        }
        if (live == null && activeMatch(playerId).isPresent()) {
            throw new ApplicationFailure(
                    409, "NAME_LOCKED", "Измените имя после завершения раунда.");
        }
        var player = persist.get();
        if (waiting.containsKey(playerId)) {
            waiting.put(playerId, player);
        }
        if (live != null) {
            live.rename(player);
        }
        if (pausedLive != null) {
            pausedLive.rename(player);
        }
        return player;
    }

    private boolean isQueued(String id) {
        return live != null
                ? live.queued(id)
                : waiting.containsKey(id)
                        || tournaments.contains(id)
                        || (pausedLive != null && pausedLive.contains(id));
    }

    public synchronized Map<String, Object> metrics() {
        var result =
                new HashMap<String, Object>(
                        Map.of(
                                "activeMatches",
                                live != null || current != null ? 1 : 0,
                                "queuedPlayers",
                                live == null ? waiting.size() : live.queuedCount(),
                                "completedMatches",
                                completedMatches,
                                "failedMatches",
                                failedMatches,
                                "storageHealthy",
                                storageHealthy,
                                "storedBytes",
                                repository.storedBytes(),
                                "storageQueue",
                                writer.getQueue().size(),
                                "maxPulseMillis",
                                maxPulseNanos / 1_000_000.0,
                                "maxActiveMatches",
                                1));
        if (live != null) {
            result.putAll(live.timingMetrics());
        } else if (current != null) {
            result.putAll(current.timingMetrics());
        }
        result.put("controlPeriodTicks", config.game().controlPeriodTicks());
        result.put(
                "controlWindowMillis",
                config.game().controlPeriodTicks() * config.game().periodMillis());
        return Map.copyOf(result);
    }

    synchronized void pulse() {
        if (closed) {
            return;
        }
        long started = nanoClock.getAsLong();
        if (live != null) {
            if (storageHealthy) {
                try {
                    advanceLive(started);
                    checkpointScores(started);
                } catch (RuntimeException failure) {
                    live.fail();
                    notifyAll();
                    storageHealthy = false;
                    failedMatches++;
                }
            }
            maxPulseNanos = Math.max(maxPulseNanos, nanoClock.getAsLong() - started);
            return;
        }
        if (current != null) {
            try {
                current.advance(started, clock.millis());
            } catch (RuntimeException e) {
                current.failed("ENGINE_ERROR");
                failedMatches++;
                discardCurrent();
            }
            if (current != null && current.view().status().equals("SAVING") && !saving) {
                var room = current;
                var record = room.completed();
                saving = true;
                try {
                    writer.execute(() -> persist(room, record));
                } catch (RejectedExecutionException e) {
                    saving = false;
                    storageHealthy = false;
                    failedMatches++;
                    current.failed("STORAGE_OVERLOAD");
                    discardCurrent();
                }
            }
        }
        if (storageHealthy) {
            dispatch();
        }
        maxPulseNanos = Math.max(maxPulseNanos, nanoClock.getAsLong() - started);
    }

    private void safePulse() {
        try {
            pulse();
        } catch (RuntimeException e) {
            synchronized (this) {
                storageHealthy = false;
                failedMatches++;
            }
        }
    }

    private void advanceLive(long now) {
        int before =
                config.live().reconEnabled()
                        ? live.observation().controlTiming().observedTick()
                        : -1;
        live.advance(now, nanoClock, clock::millis);
        if (config.live().reconEnabled()
                && live.observation().controlTiming().observedTick() != before) {
            notifyAll();
        }
    }

    private void persist(MatchRoom room, CompletedMatch record) {
        try {
            repository.save(record);
            synchronized (this) {
                tournaments.complete(record.match());
                completedMatches++;
                if (current == room) {
                    current = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                room.failed("STORAGE_ERROR");
                storageHealthy = false;
                failedMatches++;
                if (current == room) {
                    discardCurrent();
                }
            }
        } finally {
            synchronized (this) {
                saving = false;
            }
        }
    }

    private void checkpointScores(long now) {
        if (scoreSaving || now < scoreSaveDeadline) {
            return;
        }
        scoreSaveDeadline = now + config.live().scoreSaveSeconds() * 1_000_000_000L;
        var snapshot = liveScores.snapshot();
        if (snapshot.revision() == scoreSavedRevision) {
            return;
        }
        scoreSaving = true;
        var scoreOwner = live;
        writer.execute(
                () -> {
                    try {
                        liveScores.save(snapshot);
                        synchronized (this) {
                            scoreSavedRevision = snapshot.revision();
                        }
                    } catch (IOException | RuntimeException failure) {
                        synchronized (this) {
                            storageHealthy = false;
                            scoreOwner.fail();
                            failedMatches++;
                        }
                    } finally {
                        synchronized (this) {
                            scoreSaving = false;
                        }
                    }
                });
    }

    private void dispatch() {
        resumeLiveIfFinished();
        if (live != null) {
            return;
        }
        if (!storageHealthy || closed || current != null || saving) {
            return;
        }
        if (tournaments.running()) {
            var next = tournaments.pending().stream().findFirst();
            if (next.isEmpty()) {
                return;
            }
            var round = next.get();
            var byId = new HashMap<String, PlayerIdentity>();
            tournaments.season().players().forEach(p -> byId.put(p.id(), p));
            var roster = round.spawnOrder().stream().map(byId::get).toList();
            var room = create(roster, Map.of(), tournaments.season().id());
            try {
                tournaments.attach(round.number(), room.view().id());
                current = room;
            } catch (IOException e) {
                storageHealthy = false;
                failedMatches++;
            }
            return;
        }
        if (waiting.isEmpty() || nanoClock.getAsLong() < lobbyDeadlineNanos) {
            return;
        }
        var roster = new ArrayList<PlayerIdentity>();
        var iterator = waiting.values().iterator();
        while (iterator.hasNext() && roster.size() < config.capacity().participants()) {
            roster.add(iterator.next());
            iterator.remove();
        }
        var bots = new HashMap<Integer, Bot>();
        int count =
                Math.min(
                        config.game().trainingBots(),
                        config.capacity().participants() - roster.size());
        if (roster.size() == 1 && count == 0) {
            count = 1;
        }
        for (int index = 0; index < count; index++) {
            int slot = roster.size();
            Bot bot =
                    switch (index % 3) {
                        case 0 -> new SentryBot();
                        case 1 -> new HunterBot();
                        default -> new StrafeBot();
                    };
            bots.put(slot, bot);
            roster.add(
                    new PlayerIdentity(
                            "bot:" + UUID.randomUUID(), "Учебный бот " + (index + 1), false));
        }
        current = create(roster, bots, "");
        if (waiting.isEmpty()) {
            clearEmptyLobby();
        } else {
            resetLobbyDeadline();
        }
    }

    private void resumeLiveIfFinished() {
        if (pausedLive == null
                || tournaments.running()
                || current != null
                || saving
                || closed
                || !storageHealthy) {
            return;
        }
        live = pausedLive;
        pausedLive = null;
        live.resume(nanoClock.getAsLong(), clock.millis());
        for (var player : waiting.values()) {
            if (!revokedPlayers.contains(player.id())) {
                live.enqueue(player, nanoClock.getAsLong());
            }
        }
        waiting.clear();
        clearEmptyLobby();
    }

    private MatchRoom create(
            List<PlayerIdentity> roster, Map<Integer, Bot> bots, String tournamentId) {
        var players = new ArrayList<MatchPlayer>();
        for (int slot = 0; slot < roster.size(); slot++) {
            var p = roster.get(slot);
            players.add(new MatchPlayer(p.id(), p.name(), slot, bots.containsKey(slot)));
        }
        return new MatchRoom(
                UUID.randomUUID().toString(),
                players,
                ArenaMap.commons(players.size()),
                config.game().rules(),
                config.game(),
                bots,
                tournamentId,
                revokedPlayers,
                nanoClock.getAsLong(),
                clock.millis());
    }

    private MatchRoom room(String id) {
        if (current == null || !current.view().id().equals(id)) {
            throw notFound();
        }
        return current;
    }

    private void requireKnownParticipant(String id, String playerId) {
        if (view(id).players().stream().noneMatch(p -> !p.bot() && p.id().equals(playerId))) {
            throw new ApplicationFailure(403, "NOT_PARTICIPANT", "Вы не участник этого матча.");
        }
    }

    private Optional<String> activeMatch(String playerId) {
        if (live != null) {
            return live.contains(playerId) ? Optional.of(live.id()) : Optional.empty();
        }
        return current != null && current.contains(playerId)
                ? Optional.of(current.view().id())
                : Optional.empty();
    }

    private void discardCurrent() {
        terminal.put(current.view().id(), current.view());
        while (terminal.size() > 8) {
            terminal.remove(terminal.keySet().iterator().next());
        }
        current = null;
    }

    private void resetLobbyDeadline() {
        lobbyDeadlineNanos = nanoClock.getAsLong() + config.game().lobbyWaitMillis() * 1_000_000L;
        lobbyStartsAtMillis = clock.millis() + config.game().lobbyWaitMillis();
    }

    private void clearEmptyLobby() {
        if (waiting.isEmpty()) {
            lobbyDeadlineNanos = 0;
            lobbyStartsAtMillis = 0;
        }
    }

    private void ensureAvailable() {
        if (closed || !storageHealthy) {
            throw new ApplicationFailure(
                    503,
                    "STORAGE_UNAVAILABLE",
                    "Приём матчей приостановлен: проверьте хранилище сервера.");
        }
    }

    private void ensurePlayer(PlayerIdentity p) {
        if (p.revoked() || revokedPlayers.contains(p.id())) {
            throw new ApplicationFailure(403, "REVOKED", "Доступ участника отозван.");
        }
        if (!p.approved()) {
            throw new ApplicationFailure(
                    403,
                    "APPROVAL_REQUIRED",
                    "Регистрация принята. Дождитесь допуска преподавателя.");
        }
    }

    private static void validateMap(String id) {
        if (!"commons".equals(id)) {
            throw new ApplicationFailure(400, "INVALID_MAP", "Неизвестная арена.");
        }
    }

    private static ApplicationFailure notFound() {
        return new ApplicationFailure(
                404, "MATCH_NOT_FOUND", "Матч не найден или удалён по сроку хранения.");
    }

    private static ApplicationFailure capacity() {
        return new ApplicationFailure(
                503, "CAPACITY_REACHED", "Очередь заполнена. Повторите попытку позже.");
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            waiting.clear();
            notifyAll();
        }
        ticker.shutdownNow();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (liveScores != null && writer.isTerminated()) {
            try {
                liveScores.save(liveScores.snapshot());
            } catch (IOException failure) {
                System.err.println(
                        "Live scores could not be saved at shutdown; check storage before restart.");
            }
        }
    }
}

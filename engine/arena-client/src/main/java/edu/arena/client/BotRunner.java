package edu.arena.client;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.ControlTiming;
import edu.arena.core.bot.Observation;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.MatchPlayer;
import edu.arena.protocol.Messages.MatchView;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Network scheduling is separate from student decisions. Call step/run from one owner thread. */
public final class BotRunner {
    public enum State {
        WAITING,
        PLAYING,
        SPECTATING,
        RESPAWNING,
        RETRYING,
        FINISHED
    }

    public record Step(State state, int delayMillis, String matchId, String message) {}

    /** Network timing is measured by the local monotonic clock, independently of game time. */
    public record TimingStats(
            long pollCycles,
            long acceptedDecisions,
            long lateCommands,
            long duplicateCommands,
            double lastCycleMillis,
            double lastDecisionMillis,
            int lastApplyAtTick,
            int lastExpiresAtTick) {}

    public TimingStats timingStats() {
        return timingStats;
    }

    /** One immutable, internally consistent view of the most recently completed network cycle. */
    public record SessionSnapshot(Step progress, int selfSlot, Optional<MatchView> match) {
        public SessionSnapshot {
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(match, "match");
            if (selfSlot < -1
                    || (selfSlot >= 0
                            && (match.isEmpty()
                                    || selfSlot >= match.orElseThrow().state().tanks().size()))) {
                throw new IllegalArgumentException("Self slot must belong to the published match");
            }
            if (match.isPresent() && !match.orElseThrow().id().equals(progress.matchId())) {
                throw new IllegalArgumentException(
                        "Progress and snapshot must describe the same match");
            }
        }
    }

    /** Safe to read from another thread; empty until the first network cycle completes. */
    public Optional<SessionSnapshot> snapshot() {
        return Optional.ofNullable(published);
    }

    private final ArenaGateway gateway;
    private final Supplier<Bot> bots;
    private final int maxFailures;
    private final LongSupplier nanoClock;
    private Info info;
    private int failures;
    private String currentMatch;
    private String lastFinished;
    private String playerId;
    private int self = -1;
    private Bot bot;
    private long currentLife = -1;
    private Command pending;
    private boolean acknowledged;
    private boolean queued;
    private MatchView latestMatch;
    private volatile SessionSnapshot published;
    private volatile TimingStats timingStats = new TimingStats(0, 0, 0, 0, 0, 0, -1, -1);
    private long pollCycles;
    private long acceptedDecisions;
    private long lateCommands;
    private long duplicateCommands;
    private long lastDecisionNanos;
    private int lastApplyAtTick = -1;
    private int lastExpiresAtTick = -1;
    private String commandWarning = "";

    public BotRunner(ArenaGateway gateway, Supplier<Bot> bots, int maxFailures) {
        this(gateway, bots, maxFailures, System::nanoTime);
    }

    BotRunner(ArenaGateway gateway, Supplier<Bot> bots, int maxFailures, LongSupplier nanoClock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.bots = Objects.requireNonNull(bots, "bots");
        if (maxFailures < 1 || maxFailures > 100) {
            throw new IllegalArgumentException("Retry limit must be 1–100");
        }
        this.maxFailures = maxFailures;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * One scheduling cycle. Bot callbacks run on the caller thread and must return promptly; Java
     * cannot safely terminate arbitrary student code in-process. Only snapshot() has concurrent
     * readers.
     */
    public Step step() throws IOException, InterruptedException {
        long started = nanoClock.getAsLong();
        pollCycles++;
        Step progress = advance();
        long elapsed = Math.max(0, nanoClock.getAsLong() - started);
        timingStats =
                new TimingStats(
                        pollCycles,
                        acceptedDecisions,
                        lateCommands,
                        duplicateCommands,
                        elapsed / 1_000_000.0,
                        lastDecisionNanos / 1_000_000.0,
                        lastApplyAtTick,
                        lastExpiresAtTick);
        if (progress.state() == State.PLAYING) {
            // A network round trip already consumed part of the polling interval.
            progress =
                    new Step(
                            progress.state(),
                            (int) Math.max(1, progress.delayMillis() - elapsed / 1_000_000L),
                            progress.matchId(),
                            progress.message());
        }
        published = new SessionSnapshot(progress, self, Optional.ofNullable(latestMatch));
        return progress;
    }

    private Step advance() throws IOException, InterruptedException {
        try {
            if (info == null) {
                Info advertised = gateway.info();
                ProtocolCompatibility.require(advertised);
                info = advertised;
            }
            if (currentMatch == null) {
                self = -1;
                latestMatch = null;
                var me = gateway.me();
                queued = me.queued();
                if (me.revoked()) {
                    throw new ArenaException(401, "REVOKED", "Player access has been revoked", 0);
                }
                if (!me.approved()) {
                    return success(
                            State.WAITING,
                            "",
                            "Ожидание допуска преподавателя; подключение активно");
                }
                String assigned = me.matchId();
                if (assigned == null || assigned.isBlank() || assigned.equals(lastFinished)) {
                    self = -1;
                    latestMatch = null;
                    return success(
                            State.WAITING,
                            "",
                            queued
                                    ? "Заявка принята. Ожидание входа на арену"
                                    : "Подключение активно. Для входа запустите клиент снова или дождитесь преподавателя");
                }
                currentMatch = assigned;
                playerId = me.playerId();
                self = -1;
                bot = null;
                currentLife = -1;
                pending = null;
                acknowledged = false;
                latestMatch = null;
            }
            String assigned = currentMatch;
            MatchView match;
            try {
                match = gateway.observation(assigned);
            } catch (ArenaException error) {
                if (error.status() == 404 && error.code().equals("MATCH_NOT_FOUND")) {
                    return recoverAssignment(assigned);
                }
                throw error;
            }
            latestMatch = match;
            if (match.live() != null) {
                var participant =
                        match.live().players().stream()
                                .filter(p -> p.playerId().equals(playerId))
                                .findFirst();
                if (participant.isEmpty()) {
                    return recoverLiveAssignment();
                }
                var incarnation = participant.orElseThrow();
                if (self != incarnation.slot() || currentLife != incarnation.life()) {
                    self = incarnation.slot();
                    currentLife = incarnation.life();
                    bot = null;
                    pending = null;
                    acknowledged = false;
                }
            }
            if (self < 0) {
                self =
                        match.players().stream()
                                .filter(p -> p.id().equals(playerId) && !p.bot())
                                .mapToInt(MatchPlayer::slot)
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new ArenaException(
                                                        403,
                                                        "NOT_PARTICIPANT",
                                                        "Assigned match does not contain this player",
                                                        0));
            }
            if (!match.status().equals("RUNNING")) {
                if (match.status().equals("SAVING")) {
                    return success(State.SPECTATING, assigned, "Сохранение результата");
                }
                lastFinished = assigned;
                currentMatch = null;
                bot = null;
                pending = null;
                acknowledged = false;
                var place = match.placements().stream().filter(p -> p.slot() == self).findFirst();
                return success(
                        State.FINISHED,
                        assigned,
                        match.status().equals("FINISHED") && place.isPresent()
                                ? "Место " + place.get().rank() + ", очки " + place.get().points()
                                : "Раунд завершён: "
                                        + match.status()
                                        + " / "
                                        + match.finishedReason());
            }
            var timing = match.controlTiming();
            if (timing == null) {
                int tick = match.state().tick();
                int period = info.controlPeriodTicks();
                timing =
                        new ControlTiming(
                                tick,
                                tick + period,
                                tick + 2 * period,
                                period,
                                match.deadlineEpochMillis());
            }
            var view = new Observation(self, match.state(), match.map(), match.rules(), timing);
            if (view.me().health() <= 0) {
                pending = null;
                if (match.live() != null) {
                    // Keep an authenticated heartbeat during a slow respawn without sending a
                    // command.
                    gateway.me();
                    var participant =
                            match.live().players().stream()
                                    .filter(p -> p.playerId().equals(playerId))
                                    .findFirst()
                                    .orElseThrow();
                    int ticks = Math.max(0, participant.respawnAtTick() - match.state().tick());
                    int seconds = (ticks + match.rules().tickRate() - 1) / match.rules().tickRate();
                    return success(
                            State.RESPAWNING,
                            assigned,
                            "Возвращение на поле через " + seconds + " игр. с; очки сохранены");
                }
                return success(
                        State.SPECTATING, assigned, "Танк выбыл. Наблюдение до конца раунда");
            }
            if (bot == null) {
                bot = createBot(match.state().tick());
            }
            if (pending == null || pending.tick() != match.state().tick()) {
                var control = decide(view);
                pending = new Command(match.state().tick(), control);
                acknowledged = false;
            }
            if (!acknowledged) {
                try {
                    var receipt = gateway.command(assigned, pending);
                    if (receipt.acceptedTick() != pending.tick()) {
                        throw new IOException("Unexpected command acknowledgement");
                    }
                    if (match.controlTiming() != null
                            && (receipt.applyAtTick() != timing.applyAtTick()
                                    || receipt.expiresAtTick() != timing.expiresAtTick())) {
                        throw new IOException("Unexpected command execution interval");
                    }
                    acceptedDecisions++;
                    if (receipt.duplicate()) {
                        duplicateCommands++;
                    }
                    lastApplyAtTick = receipt.applyAtTick();
                    lastExpiresAtTick = receipt.expiresAtTick();
                    commandWarning = "";
                    acknowledged = true;
                } catch (ArenaException error) {
                    if (match.live() != null
                            && ((error.status() == 403 && error.code().equals("NOT_PARTICIPANT"))
                                    || (error.status() == 409
                                            && error.code().equals("NOT_IN_ARENA")))) {
                        return recoverLiveAssignment();
                    } else if (error.status() == 409
                            && (error.code().equals("STALE_TICK")
                                    || error.code().equals("MATCH_FINISHED")
                                    || error.code().equals("ELIMINATED")
                                    || error.code().equals("RESPAWNING")
                                    || error.code().equals("PLAYER_ELIMINATED"))) {
                        acknowledged = true;
                        if (error.code().equals("STALE_TICK")) {
                            lateCommands++;
                            commandWarning =
                                    "Команда опоздала; опоздавших команд: "
                                            + lateCommands
                                            + ". Проверьте задержку сети и время decide.";
                        } else {
                            commandWarning = "Команда не применена: " + error.code();
                        }
                    } else {
                        throw error;
                    }
                }
            }
            return success(
                    State.PLAYING,
                    assigned,
                    commandWarning.isEmpty() ? "Матч идёт" : commandWarning);
        } catch (StudentCodeFailure failure) {
            // Retrying network I/O must never hide or repeatedly execute a broken student strategy.
            throw failure;
        } catch (IOException failure) {
            if (failure instanceof ArenaException error && !error.transientFailure()) {
                throw failure;
            }
            if (++failures >= maxFailures) {
                throw new IOException(
                        "Связь не восстановилась после "
                                + maxFailures
                                + " попыток. Проверьте адрес, сеть и состояние сервера.",
                        failure);
            }
            int delay = Math.min(5000, 250 * (1 << Math.min(failures - 1, 4)));
            if (info != null) {
                delay = Math.max(delay, activePollMillis());
            }
            if (failure instanceof ArenaException error) {
                delay = Math.max(delay, error.retryAfterSeconds() * 1000);
            }
            return new Step(
                    State.RETRYING,
                    delay,
                    currentMatch == null ? "" : currentMatch,
                    "Повтор подключения " + failures + "/" + maxFailures);
        } catch (RuntimeException invalidSnapshot) {
            throw new IOException(
                    "Не удалось обработать снимок сервера: "
                            + invalidSnapshot.getClass().getSimpleName(),
                    invalidSnapshot);
        }
    }

    private Bot createBot(int tick) throws StudentCodeFailure {
        final Bot created;
        try {
            created = bots.get();
        } catch (RuntimeException failure) {
            throw studentFailure("создании экземпляра бота", tick, failure);
        }
        if (created == null) {
            throw new StudentCodeFailure(
                    "Фабрика бота вернула null на шаге " + tick + ". Верните новый экземпляр Bot.",
                    new NullPointerException("Bot supplier returned null"));
        }
        return created;
    }

    private edu.arena.core.model.Control decide(Observation view) throws StudentCodeFailure {
        final edu.arena.core.model.Control control;
        long started = nanoClock.getAsLong();
        try {
            control = bot.decide(view);
        } catch (RuntimeException failure) {
            throw studentFailure("decide", view.state().tick(), failure);
        } finally {
            lastDecisionNanos = Math.max(0, nanoClock.getAsLong() - started);
        }
        if (control == null) {
            throw new StudentCodeFailure(
                    "Метод decide вернул null на шаге "
                            + view.state().tick()
                            + ". Верните Control, например Control.idle(observation.me().position()).",
                    new NullPointerException("Bot returned null"));
        }
        return control;
    }

    private static StudentCodeFailure studentFailure(
            String operation, int tick, RuntimeException cause) {
        String location =
                java.util.Arrays.stream(cause.getStackTrace())
                        .filter(frame -> !frame.getClassName().equals(BotRunner.class.getName()))
                        .filter(
                                frame ->
                                        !frame.getClassName().startsWith("java.")
                                                && !frame.getClassName().startsWith("jdk."))
                        .findFirst()
                        .map(
                                frame ->
                                        frame.getFileName() != null && frame.getLineNumber() >= 0
                                                ? frame.getFileName() + ":" + frame.getLineNumber()
                                                : frame.getClassName()
                                                        + "."
                                                        + frame.getMethodName())
                        .map(source -> " (" + source + ")")
                        .orElse("");
        return new StudentCodeFailure(
                "Ошибка в "
                        + operation
                        + " на шаге "
                        + tick
                        + location
                        + ": "
                        + cause.getClass().getSimpleName()
                        + ". Исправьте бота и запустите клиент снова.",
                cause);
    }

    private static final class StudentCodeFailure extends IOException {
        private static final long serialVersionUID = 1L;

        private StudentCodeFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public void run(boolean continuous, Consumer<String> progress)
            throws IOException, InterruptedException {
        run(continuous, false, progress);
    }

    /**
     * Runs a confirmed queue admission; a vanished queue entry is not a future tournament
     * assignment.
     */
    public void runQueued(Consumer<String> progress) throws IOException, InterruptedException {
        run(false, true, progress);
    }

    private void run(boolean continuous, boolean requireQueue, Consumer<String> progress)
            throws IOException, InterruptedException {
        Objects.requireNonNull(progress, "progress");
        String previous = "";
        while (!Thread.currentThread().isInterrupted()) {
            Step result = step();
            if (requireQueue
                    && result.state() == State.WAITING
                    && !queued
                    && result.matchId().isEmpty()) {
                throw new IOException(
                        "Позиции в очереди и активного назначения больше нет. "
                                + "Проверьте историю боёв в браузере: раунд мог завершиться до восстановления связи. "
                                + "Если нужен новый бой, запустите клиент снова.");
            }
            String label = result.matchId() + " " + result.message();
            if (!label.equals(previous)) {
                progress.accept(label.strip());
                previous = label;
            }
            if (result.state() == State.FINISHED && !continuous) {
                if (!requireQueue || latestMatch == null || !latestMatch.ranked()) {
                    return;
                }
                var next = gateway.me();
                if (!next.queued()
                        && (next.matchId() == null || next.matchId().equals(result.matchId()))) {
                    return;
                }
            }
            Thread.sleep(result.delayMillis());
        }
    }

    private Step success(State state, String match, String message) {
        failures = 0;
        int delay =
                state == State.WAITING || state == State.FINISHED
                        ? Math.max(1000, info.playerPollMillis())
                        : state == State.SPECTATING || state == State.RESPAWNING
                                ? Math.max(500, info.spectatorPollMillis())
                                : activePollMillis();
        return new Step(state, delay, match, message);
    }

    private int activePollMillis() {
        int period =
                Math.max(
                        (1000 + info.maxStepRate() - 1) / info.maxStepRate(),
                        (info.commandTimeoutMillis() + info.controlPeriodTicks() - 1)
                                / info.controlPeriodTicks());
        return Math.max(info.playerPollMillis(), (period + 1) / 2);
    }

    private Step recoverAssignment(String missing) throws IOException, InterruptedException {
        // Retention can remove a completed round while this client is offline.
        var me = gateway.me();
        queued = me.queued();
        if (me.revoked()) {
            throw new ArenaException(401, "REVOKED", "Player access has been revoked", 0);
        }
        lastFinished = missing;
        String assigned = me.matchId();
        currentMatch =
                assigned == null || assigned.isBlank() || assigned.equals(missing)
                        ? null
                        : assigned;
        playerId = me.playerId();
        self = -1;
        bot = null;
        currentLife = -1;
        pending = null;
        acknowledged = false;
        latestMatch = null;
        return success(
                State.WAITING,
                currentMatch == null ? "" : currentMatch,
                "Прошлый матч недоступен. Назначение проверено заново");
    }

    private Step recoverLiveAssignment() throws IOException, InterruptedException {
        // The fetched roster already excludes us. Drop stale ownership before a fallible network
        // call.
        self = -1;
        bot = null;
        currentLife = -1;
        pending = null;
        acknowledged = false;
        latestMatch = null;
        var me = gateway.me();
        queued = me.queued();
        currentMatch = me.matchId();
        playerId = me.playerId();
        return success(
                State.WAITING,
                currentMatch == null ? "" : currentMatch,
                "Участие на поле завершено. Для повторного входа запустите клиент снова или дождитесь преподавателя");
    }
}

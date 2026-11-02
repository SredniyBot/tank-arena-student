package edu.arena.client;

import edu.arena.core.model.Control;
import edu.arena.core.recon.ReconBot;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.ReconFrame;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One owner thread polls personal observations and submits at most one decision per window. */
public final class ReconBotRunner {
    public enum State {
        WAITING,
        PLAYING,
        RESPAWNING,
        RETRYING,
        FINISHED
    }

    public record Step(State state, int delayMillis, String message) {}

    private final ReconGateway gateway;
    private final Supplier<ReconBot> factory;
    private Info info;
    private String matchId;
    private ReconBot bot;
    private long life = -1;
    private int self = -1;
    private int lastSnapshotTick = -1;
    private Command pending;
    private boolean acknowledged;
    private int failures;
    private volatile ReconFrame published;
    private volatile long lateCommands;
    private volatile long skippedWindows;
    private volatile long synchronizationWindows;
    private int lastDecisionTick = -1;

    public ReconBotRunner(ReconGateway gateway, Supplier<ReconBot> factory) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** The published frame contains exactly the information received by this client. */
    public Optional<ReconFrame> snapshot() {
        return Optional.ofNullable(published);
    }

    public long lateCommands() {
        return lateCommands;
    }

    /** Decision windows missed between two decisions within the same life. */
    public long skippedWindows() {
        return skippedWindows;
    }

    /** Initial windows deliberately observed only to synchronize with the shared clock. */
    public long synchronizationWindows() {
        return synchronizationWindows;
    }

    public Step step() throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            if (info == null) {
                info = gateway.info();
                ProtocolCompatibility.require(info);
            }
            if (matchId == null) {
                var me = gateway.me();
                if (me.revoked()) {
                    throw new ArenaException(401, "REVOKED", "Доступ отозван преподавателем.", 0);
                }
                if (!me.approved()) {
                    return success(State.WAITING, 1000, "Ожидание допуска преподавателя");
                }
                if (me.matchId() == null || me.matchId().isBlank()) {
                    return success(
                            State.WAITING,
                            500,
                            me.queued()
                                    ? "Ожидание места в команде"
                                    : "Нет активного назначения; запустите Main после допуска");
                }
                matchId = me.matchId();
            }
            boolean waitForNext = lastSnapshotTick >= 0 && (pending == null || acknowledged);
            ReconFrame frame =
                    waitForNext
                            ? gateway.reconAfter(matchId, lastSnapshotTick)
                            : gateway.recon(matchId);
            if (lastSnapshotTick < 0 && frame.status().equals("RUNNING")) {
                int bootstrapTick = frame.observation().timing().observedTick();
                synchronizationWindows++;
                frame = gateway.reconAfter(matchId, bootstrapTick);
                if (frame.observation().timing().observedTick() <= bootstrapTick) {
                    lastSnapshotTick = bootstrapTick;
                    published = frame;
                    return success(State.WAITING, 1, "Ожидается следующий общий такт");
                }
                waitForNext = true;
            }
            if (!frame.matchId().equals(matchId)) {
                throw new IOException("Наблюдение относится к другому матчу");
            }
            published = frame;
            if (!frame.status().equals("RUNNING")) {
                return success(State.FINISHED, 0, "Арена остановлена: " + frame.status());
            }
            var observation = frame.observation();
            int tick = observation.timing().observedTick();
            if (observation.life() != life || observation.self() != self) {
                life = observation.life();
                self = observation.self();
                bot = null;
                pending = null;
                acknowledged = false;
                lastDecisionTick = -1;
                if (!waitForNext && lastSnapshotTick >= 0) {
                    lastSnapshotTick = tick;
                    synchronizationWindows++;
                    return success(State.WAITING, 1, "Ожидается следующий общий такт");
                }
            }
            if (!waitForNext && lastSnapshotTick >= 0 && tick > lastSnapshotTick) {
                pending = null;
                acknowledged = false;
                lastSnapshotTick = tick;
                synchronizationWindows++;
                return success(State.WAITING, 1, "Ожидается следующий общий такт");
            }
            lastSnapshotTick = tick;
            if (observation.me().health() <= 0) {
                pending = null;
                return success(
                        State.RESPAWNING,
                        pollMillis(),
                        "Ожидание нового танка; команда " + (observation.team() + 1));
            }
            if (bot == null) {
                try {
                    bot = Objects.requireNonNull(factory.get(), "Factory returned null");
                } catch (RuntimeException failure) {
                    throw new StudentFailure("Не удалось создать бота", failure);
                }
            }
            if (pending == null || pending.tick() != tick) {
                if (lastDecisionTick >= 0 && tick > lastDecisionTick) {
                    skippedWindows +=
                            Math.max(
                                    0,
                                    (tick - lastDecisionTick)
                                                    / observation.timing().controlPeriodTicks()
                                            - 1);
                }
                lastDecisionTick = tick;
                final Control control;
                try {
                    control =
                            Objects.requireNonNull(bot.decide(observation), "decide returned null");
                } catch (RuntimeException failure) {
                    throw new StudentFailure("Ошибка decide на тике " + tick, failure);
                }
                pending = new Command(tick, control);
                acknowledged = false;
            }
            String message = "Команда " + (observation.team() + 1) + "; личное наблюдение " + tick;
            if (!acknowledged) {
                try {
                    var receipt = gateway.command(matchId, pending);
                    if (receipt.acceptedTick() != pending.tick()
                            || receipt.applyAtTick() != observation.timing().applyAtTick()
                            || receipt.expiresAtTick() != observation.timing().expiresAtTick()) {
                        throw new IOException(
                                "Подтверждение команды содержит другой интервал исполнения");
                    }
                    acknowledged = true;
                } catch (ArenaException error) {
                    if (error.status() == 409
                            && (error.code().equals("STALE_TICK")
                                    || error.code().equals("RESPAWNING")
                                    || error.code().equals("NOT_IN_ARENA"))) {
                        acknowledged = true;
                        if (error.code().equals("STALE_TICK")) {
                            lateCommands++;
                            message =
                                    "Команда опоздала; всего: "
                                            + lateCommands
                                            + ". Проверьте время decide и сеть.";
                        }
                    } else {
                        throw error;
                    }
                }
            }
            int delay =
                    (int) Math.max(1, pollMillis() - (System.nanoTime() - started) / 1_000_000L);
            return success(State.PLAYING, delay, message);
        } catch (StudentFailure failure) {
            throw failure;
        } catch (ArenaException error) {
            if (error.code().equals("WAIT_IN_PROGRESS")) {
                return retry(error);
            }
            if (error.code().equals("OBSERVATION_PENDING")) {
                return success(State.WAITING, pollMillis(), "Ожидается общий такт наблюдения");
            }
            if (error.code().equals("NOT_PARTICIPANT") || error.code().equals("MATCH_NOT_FOUND")) {
                matchId = null;
                published = null;
                lastSnapshotTick = -1;
                lastDecisionTick = -1;
                self = -1;
                life = -1;
                bot = null;
                pending = null;
                return success(State.WAITING, 500, "Назначение изменилось; проверяем подключение");
            }
            if (!error.transientFailure()) {
                throw error;
            }
            return retry(error);
        } catch (IOException failure) {
            return retry(failure);
        }
    }

    private Step retry(IOException failure) throws IOException {
        if (++failures >= 10) {
            throw new IOException("Связь не восстановилась после десяти попыток", failure);
        }
        int delay = Math.min(5000, 250 * (1 << Math.min(failures - 1, 4)));
        if (failure instanceof ArenaException error) {
            delay = Math.max(delay, error.retryAfterSeconds() * 1000);
        }
        return new Step(State.RETRYING, delay, "Повтор подключения " + failures + "/10");
    }

    private Step success(State state, int delay, String message) {
        failures = 0;
        return new Step(state, delay, message);
    }

    private int pollMillis() {
        return info == null ? 100 : info.playerPollMillis();
    }

    public void run(Consumer<String> progress) throws IOException, InterruptedException {
        Objects.requireNonNull(progress, "progress");
        State previous = null;
        long reportedLate = -1;
        long reportedSkipped = -1;
        while (!Thread.currentThread().isInterrupted()) {
            Step step = step();
            if (step.state() != previous
                    || lateCommands != reportedLate
                    || skippedWindows != reportedSkipped) {
                String message =
                        previous != null
                                        && (lateCommands != reportedLate
                                                || skippedWindows != reportedSkipped)
                                ? "Опоздало команд: "
                                        + lateCommands
                                        + "; пропущено окон: "
                                        + skippedWindows
                                        + ". Проверьте время decide и сеть."
                                : step.message();
                progress.accept(message);
                previous = step.state();
                reportedLate = lateCommands;
                reportedSkipped = skippedWindows;
            }
            if (step.state() == State.FINISHED) {
                return;
            }
            Thread.sleep(step.delayMillis());
        }
    }

    private static final class StudentFailure extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        StudentFailure(String message, Throwable cause) {
            super(message + ". Исправьте код и запустите клиент снова.", cause);
        }
    }
}

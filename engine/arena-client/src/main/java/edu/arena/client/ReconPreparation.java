package edu.arena.client;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Tests timing before joining the arena. No student decisions or game commands are sent. */
final class ReconPreparation {
    private static final int REQUIRED_WINDOWS = 15;
    private static final long TIMEOUT_NANOS = 8_000_000_000L;

    private ReconPreparation() {}

    static void await(ArenaClient client, Consumer<String> progress)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        int lastTick = -1;
        int consecutive = 0;
        int attempts = 0;
        int expired = 0;
        int limited = 0;
        int limitedInRow = 0;
        String round = "";
        progress.accept("Подготовка соединения до входа на поле");
        while (remaining(start) > 0) {
            try {
                long budget = remaining(start);
                if (budget <= 0) {
                    break;
                }
                var first = client.reconPreparation(Duration.ofNanos(budget));
                limitedInRow = 0;
                if (!first.status().equals("RUNNING")) {
                    throw new Failure("Арена недоступна для подготовки.");
                }
                var timing = first.observation().timing();
                String currentRound = first.observation().roundId();
                int tick = timing.observedTick();
                if (!currentRound.equals(round)) {
                    round = currentRound;
                    consecutive = 0;
                    lastTick = -1;
                }
                if (tick <= lastTick) {
                    pause(start, 50);
                    continue;
                }
                if (lastTick >= 0 && tick - lastTick > timing.controlPeriodTicks()) {
                    expired += (tick - lastTick) / timing.controlPeriodTicks() - 1;
                    consecutive = 0;
                }
                long remaining = remaining(start);
                if (remaining <= 0) {
                    break;
                }
                var check = client.reconPreparation(Duration.ofNanos(remaining));
                int confirmed = check.observation().timing().observedTick();
                attempts++;
                if (confirmed == tick && check.observation().roundId().equals(round)) {
                    consecutive++;
                } else {
                    consecutive = 0;
                    expired +=
                            check.observation().roundId().equals(round) && confirmed > tick
                                    ? Math.max(1, (confirmed - tick) / timing.controlPeriodTicks())
                                    : 1;
                }
                lastTick = confirmed;
                if (consecutive >= REQUIRED_WINDOWS) {
                    progress.accept(
                            "Проверка соединения завершена: "
                                    + attempts
                                    + " окон, истекло "
                                    + expired
                                    + ", ограничено запросов "
                                    + limited
                                    + ". Входим на поле.");
                    return;
                }
                if (attempts % 5 == 0) {
                    progress.accept(
                            "Подготовка: "
                                    + consecutive
                                    + "/"
                                    + REQUIRED_WINDOWS
                                    + ", истекло окон "
                                    + expired
                                    + ", ограничено запросов "
                                    + limited);
                }
            } catch (ArenaException unavailable) {
                if (!unavailable.transientFailure()) {
                    throw unavailable;
                }
                if (unavailable.status() != 429) {
                    report(
                            progress,
                            "Сервер временно недоступен для проверки",
                            attempts,
                            expired,
                            limited);
                    return;
                }
                consecutive = 0;
                lastTick = -1;
                limited++;
                limitedInRow++;
                if (limitedInRow >= 6) {
                    report(progress, "Сервер ограничил подготовку", attempts, expired, limited);
                    return;
                }
                pause(
                        start,
                        Math.max(1000, unavailable.retryAfterSeconds() * 1000L)
                                + ThreadLocalRandom.current().nextInt(0, 501));
            } catch (Failure failure) {
                throw failure;
            } catch (IOException unavailable) {
                report(progress, "Не удалось проверить соединение", attempts, expired, limited);
                return;
            }
        }
        report(progress, "Подготовку не удалось завершить за 8 секунд", attempts, expired, limited);
    }

    private static void report(
            Consumer<String> progress, String reason, int attempts, int expired, int limited) {
        progress.accept(
                reason
                        + ": проверено "
                        + attempts
                        + ", истекло "
                        + expired
                        + ", ограничено запросов "
                        + limited
                        + ". Входим без проверки; возможны опоздания.");
    }

    private static long remaining(long started) {
        return TIMEOUT_NANOS - (System.nanoTime() - started);
    }

    private static void pause(long started, long millis) throws InterruptedException {
        long remaining = remaining(started);
        if (remaining > 0) {
            Thread.sleep(Math.max(1, Math.min(millis, remaining / 1_000_000L)));
        }
    }

    static final class Failure extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }
}

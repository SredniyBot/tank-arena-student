package edu.arena.client;

import edu.arena.protocol.Messages;
import java.io.IOException;
import java.util.function.Consumer;

/** Confirms one admission without repeating a request whose outcome is unknown. */
final class ArenaAdmission {
    private ArenaAdmission() {}

    static Messages.QueueReply enqueue(
            ArenaClient client, String mode, String opponent, String map, Consumer<String> progress)
            throws IOException, InterruptedException {
        return enqueue(client, mode, opponent, map, progress, null);
    }

    @FunctionalInterface
    interface Preparation {
        void await() throws IOException, InterruptedException;
    }

    static Messages.QueueReply enqueue(
            ArenaClient client,
            String mode,
            String opponent,
            String map,
            Consumer<String> progress,
            Preparation preparation)
            throws IOException, InterruptedException {
        String previousAssignment = null;
        boolean baselineKnown = false;
        for (int attempt = 0; attempt < 6; attempt++) {
            boolean admissionSent = false;
            try {
                if (!baselineKnown) {
                    var before = client.me();
                    requireActive(before);
                    if (!before.approved()) {
                        progress.accept(
                                "Ожидание допуска преподавателя. После подтверждения войдём в очередь автоматически.");
                        do {
                            Thread.sleep(1000);
                            before = client.me();
                            requireActive(before);
                        } while (!before.approved());
                    }
                    previousAssignment = before.matchId();
                    if (before.queued()) {
                        return new Messages.QueueReply(true, before.matchId());
                    }
                    if (preparation != null
                            && (before.matchId() == null || before.matchId().isBlank())) {
                        preparation.await();
                    }
                    baselineKnown = true;
                }
                admissionSent = true;
                return client.queue(mode, opponent, map);
            } catch (IOException failure) {
                if (failure instanceof ReconPreparation.Failure) {
                    throw failure;
                }
                if (failure instanceof ArenaException error && !error.transientFailure()) {
                    throw error;
                }
                // The first request may have succeeded while its response was lost.
                try {
                    if (baselineKnown) {
                        var me = client.me();
                        if (me.queued()) {
                            return new Messages.QueueReply(true, me.matchId());
                        }
                        if (me.matchId() != null && !me.matchId().isBlank()) {
                            // A new active assignment proves that the admission reached the server.
                            if (!me.matchId().equals(previousAssignment)) {
                                return new Messages.QueueReply(false, me.matchId());
                            }
                            if (assignmentActive(client, me.matchId())) {
                                return new Messages.QueueReply(false, me.matchId());
                            }
                        }
                    }
                } catch (ArenaException error) {
                    // An old completed assignment may already have been removed by replay
                    // retention.
                    if (!(error.status() == 404 && error.code().equals("MATCH_NOT_FOUND"))
                            && !error.transientFailure()) {
                        throw error;
                    }
                } catch (IOException ignored) {
                }
                if (admissionSent && !(failure instanceof ArenaException)) {
                    throw new IOException(
                            "Ответ на вход в очередь потерян, а действующее назначение не подтверждено. "
                                    + "Проверьте историю боёв в браузере перед повторным запуском клиента: раунд мог уже завершиться.",
                            failure);
                }
                if (attempt == 5) {
                    throw new IOException(
                            "Не удалось войти в очередь после шести попыток.", failure);
                }
                int delay = Math.min(5000, 250 * (1 << attempt));
                if (failure instanceof ArenaException error) {
                    delay = Math.max(delay, error.retryAfterSeconds() * 1000);
                }
                Thread.sleep(delay);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private static void requireActive(Messages.Me participant) throws ArenaException {
        if (participant.revoked()) {
            throw new ArenaException(401, "REVOKED", "Доступ отозван преподавателем.", 0);
        }
    }

    private static boolean assignmentActive(ArenaClient client, String matchId)
            throws IOException, InterruptedException {
        try {
            var assigned = client.match(matchId);
            return assigned.status().equals("RUNNING") || assigned.status().equals("SAVING");
        } catch (ArenaException failure) {
            if (!failure.code().equals("RECON_PRIVATE")) {
                throw failure;
            }
            try {
                return client.recon(matchId).status().equals("RUNNING");
            } catch (ArenaException pending) {
                if (pending.code().equals("OBSERVATION_PENDING")) {
                    // The server checked membership; the next common snapshot has not opened yet.
                    return true;
                }
                throw pending;
            }
        }
    }
}

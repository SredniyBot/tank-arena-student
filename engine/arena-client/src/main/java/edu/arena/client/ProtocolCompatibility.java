package edu.arena.client;

import edu.arena.protocol.Messages;

/** One preflight shared by CLI entry points and long-running bot sessions. */
final class ProtocolCompatibility {
    private ProtocolCompatibility() {}

    static void require(Messages.Info info) throws ArenaException {
        if (!Messages.VERSION.equals(info.version())
                || info.playerPollMillis() < 20
                || info.playerPollMillis() > 60_000
                || info.maxStepRate() < 1
                || info.commandTimeoutMillis() < 1
                || info.controlPeriodTicks() < 1
                || info.controlPeriodTicks() > 40) {
            throw new ArenaException(
                    400,
                    "INCOMPATIBLE_SERVER",
                    "Нужны сервер и SDK со схемой "
                            + Messages.VERSION
                            + "; получена "
                            + info.version()
                            + ". Обновите комплект целиком и проверьте настройки опроса.",
                    0);
        }
    }
}

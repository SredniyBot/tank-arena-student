package edu.arena.server.config;

import edu.arena.core.model.GameRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Validated startup configuration; changes require a restart, never mid-match. */
public record ServerConfig(
        Network network, Game game, Capacity capacity, Path dataDirectory, Live live) {
    public ServerConfig(Network network, Game game, Capacity capacity, Path dataDirectory) {
        this(network, game, capacity, dataDirectory, new Live(false, 2000, 1500, 15, 600, 5));
    }

    public record Live(
            boolean enabled,
            int respawnMillis,
            int spawnProtectionMillis,
            int idleTimeoutSeconds,
            int historyFrames,
            int scoreSaveSeconds,
            boolean deliveryEnabled,
            boolean reconEnabled,
            int cargoSpeedPercent,
            int visionRadius,
            int reconSourceCount,
            boolean controlZonesEnabled,
            boolean combatEnabled,
            int teamCount) {
        public Live {
            if (reconSourceCount != 2 && reconSourceCount != 4) {
                throw new IllegalArgumentException("Recon source count must be two or four");
            }
            if (teamCount < 2 || teamCount > 4) {
                throw new IllegalArgumentException("Team count must be between two and four");
            }
        }

        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled,
                boolean reconEnabled,
                int cargoSpeedPercent,
                int visionRadius,
                int reconSourceCount,
                boolean controlZonesEnabled) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    reconEnabled,
                    cargoSpeedPercent,
                    visionRadius,
                    reconSourceCount,
                    controlZonesEnabled,
                    false,
                    4);
        }

        /** Preserve the previous constructor and its delivery rules. */
        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled,
                boolean reconEnabled,
                int cargoSpeedPercent,
                int visionRadius,
                int reconSourceCount) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    reconEnabled,
                    cargoSpeedPercent,
                    visionRadius,
                    reconSourceCount,
                    false);
        }

        /** Preserve existing callers' four-source layout. */
        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled,
                boolean reconEnabled,
                int cargoSpeedPercent,
                int visionRadius) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    reconEnabled,
                    cargoSpeedPercent,
                    visionRadius,
                    4);
        }

        /** Retain the previous recon visibility when callers configure only cargo speed. */
        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled,
                boolean reconEnabled,
                int cargoSpeedPercent) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    reconEnabled,
                    cargoSpeedPercent,
                    340);
        }

        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled,
                boolean reconEnabled) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    reconEnabled,
                    100);
        }

        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds,
                boolean deliveryEnabled) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    deliveryEnabled,
                    false);
        }

        public Live(
                boolean enabled,
                int respawnMillis,
                int spawnProtectionMillis,
                int idleTimeoutSeconds,
                int historyFrames,
                int scoreSaveSeconds) {
            this(
                    enabled,
                    respawnMillis,
                    spawnProtectionMillis,
                    idleTimeoutSeconds,
                    historyFrames,
                    scoreSaveSeconds,
                    false);
        }

        public int respawnTicks(int rate) {
            return ticks(respawnMillis, rate);
        }

        public int protectionTicks(int rate) {
            return ticks(spawnProtectionMillis, rate);
        }

        private static int ticks(int millis, int rate) {
            return (int) (((long) millis * rate + 999) / 1000);
        }
    }

    public record Network(
            String host,
            int port,
            int workers,
            int requestQueue,
            int maxConnections,
            int globalRps,
            int ipRps,
            int playerRps,
            int enrollmentRps,
            int replayRps,
            int globalReplayRps,
            int spectatorPollMillis,
            int requestTimeoutSeconds,
            boolean allowRemote) {}

    public record Game(
            int simulationHz,
            int maxStepRate,
            int roundSeconds,
            int deadlineMillis,
            int missedLimit,
            int startupGraceMillis,
            int lobbyWaitMillis,
            int trainingBots,
            int reloadMillis,
            int shieldDurationMillis,
            int shieldCooldownMillis,
            int controlPeriodTicks,
            int bulletSpeed) {
        public Game {
            if (bulletSpeed < 120 || bulletSpeed > 900) {
                throw new IllegalArgumentException("Projectile speed must be between 120 and 900");
            }
        }

        /** Preserve the previous projectile speed for callers using the original constructor. */
        public Game(
                int simulationHz,
                int maxStepRate,
                int roundSeconds,
                int deadlineMillis,
                int missedLimit,
                int startupGraceMillis,
                int lobbyWaitMillis,
                int trainingBots,
                int reloadMillis,
                int shieldDurationMillis,
                int shieldCooldownMillis,
                int controlPeriodTicks) {
            this(
                    simulationHz,
                    maxStepRate,
                    roundSeconds,
                    deadlineMillis,
                    missedLimit,
                    startupGraceMillis,
                    lobbyWaitMillis,
                    trainingBots,
                    reloadMillis,
                    shieldDurationMillis,
                    shieldCooldownMillis,
                    controlPeriodTicks,
                    420);
        }

        public Game(
                int simulationHz,
                int maxStepRate,
                int roundSeconds,
                int deadlineMillis,
                int missedLimit,
                int startupGraceMillis,
                int lobbyWaitMillis,
                int trainingBots,
                int reloadMillis,
                int shieldDurationMillis,
                int shieldCooldownMillis) {
            this(
                    simulationHz,
                    maxStepRate,
                    roundSeconds,
                    deadlineMillis,
                    missedLimit,
                    startupGraceMillis,
                    lobbyWaitMillis,
                    trainingBots,
                    reloadMillis,
                    shieldDurationMillis,
                    shieldCooldownMillis,
                    5);
        }

        public Game(
                int simulationHz,
                int maxStepRate,
                int roundSeconds,
                int deadlineMillis,
                int missedLimit,
                int startupGraceMillis,
                int lobbyWaitMillis,
                int trainingBots) {
            this(
                    simulationHz,
                    maxStepRate,
                    roundSeconds,
                    deadlineMillis,
                    missedLimit,
                    startupGraceMillis,
                    lobbyWaitMillis,
                    trainingBots,
                    800,
                    2000,
                    8000);
        }

        public int periodMillis() {
            return Math.max((1000 + maxStepRate - 1) / maxStepRate, deadlineMillis);
        }

        public GameRules rules() {
            var base = GameRules.standard(simulationHz, roundSeconds);
            return new GameRules(
                    base.tickRate(),
                    base.maxTicks(),
                    base.hitPoints(),
                    base.moveSpeed(),
                    base.turnSpeed(),
                    base.turretSpeed(),
                    bulletSpeed,
                    ticks(reloadMillis),
                    base.tankRadius(),
                    base.bulletRadius(),
                    base.zoneStartTick(),
                    base.zoneEndTick(),
                    base.zoneDamageIntervalTicks(),
                    ticks(shieldDurationMillis),
                    ticks(shieldCooldownMillis));
        }

        private int ticks(int millis) {
            return (int) (((long) millis * simulationHz + 999) / 1000);
        }
    }

    public record Capacity(
            int activeMatches,
            int queuedPlayers,
            int players,
            int invites,
            int storedMatches,
            long historyBytes,
            int tournamentPlayers,
            int participants,
            int ipKeys) {}

    public static ServerConfig load(Path file) throws IOException {
        var values = new Properties();
        try (var in = Files.newInputStream(file)) {
            values.load(in);
        }
        return from(values, Path.of(".").toAbsolutePath());
    }

    public static ServerConfig from(Properties p, Path base) {
        var reader = new Reader(p);
        var host = reader.text("network.host", "127.0.0.1");
        var allow = reader.bool("network.allowRemote", false);
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host) && !allow) {
            throw new IllegalArgumentException(
                    "Remote binding requires network.allowRemote=true and a protected TLS proxy.");
        }
        var network =
                new Network(
                        host,
                        reader.number("network.port", 8080, 0, 65535),
                        reader.number("network.workers", 8, 2, 64),
                        reader.number("network.requestQueue", 32, 1, 512),
                        reader.number("network.maxConnections", 64, 8, 1024),
                        reader.number("network.globalRps", 1500, 20, 10000),
                        reader.number("network.ipRps", 1400, 10, 10000),
                        reader.number("network.playerRps", 40, 10, 1000),
                        reader.number("network.enrollmentRps", 1, 1, 1000),
                        reader.number("network.replayRps", 2, 1, 100),
                        reader.number("network.globalReplayRps", 8, 1, 1000),
                        reader.number("network.spectatorPollMillis", 500, 100, 5000),
                        reader.number("network.requestTimeoutSeconds", 5, 1, 30),
                        allow);
        var game =
                new Game(
                        reader.number("game.simulationHz", 20, 10, 40),
                        reader.number("game.maxStepRate", 10, 1, 40),
                        reader.number("game.roundSeconds", 60, 5, 180),
                        reader.number("game.deadlineMillis", 100, 25, 5000),
                        reader.number("game.missedLimit", 10, 2, 100),
                        reader.number("game.startupGraceMillis", 2000, 0, 30000),
                        reader.number("game.lobbyWaitMillis", 5000, 1000, 60000),
                        reader.number("game.trainingBots", 3, 0, 16),
                        reader.number("game.reloadMillis", 800, 100, 10000),
                        reader.number("game.shieldDurationMillis", 2000, 100, 10000),
                        reader.number("game.shieldCooldownMillis", 8000, 100, 60000),
                        reader.number("game.controlPeriodTicks", 5, 1, 40),
                        reader.number("game.bulletSpeed", 420, 120, 900));
        if (game.shieldCooldownMillis() < game.shieldDurationMillis()) {
            throw new IllegalArgumentException(
                    "shieldCooldownMillis must not be shorter than shieldDurationMillis");
        }
        if (game.startupGraceMillis() != 0 && game.startupGraceMillis() < 1000) {
            throw new IllegalArgumentException(
                    "startupGraceMillis must be zero or between 1000 and 30000");
        }
        if (game.maxStepRate() > game.simulationHz()) {
            throw new IllegalArgumentException("maxStepRate must not exceed simulationHz");
        }
        int commandsPerSecond = (1000 + game.periodMillis() - 1) / game.periodMillis();
        if (network.playerRps() < commandsPerSecond) {
            throw new IllegalArgumentException(
                    "network.playerRps must be at least "
                            + commandsPerSecond
                            + " to accept one command per game window; increase the budget or game.deadlineMillis");
        }
        var capacity =
                new Capacity(
                        reader.number("capacity.activeMatches", 1, 1, 1),
                        reader.number("capacity.queuedPlayers", 32, 2, 256),
                        reader.number("capacity.players", 128, 2, 1000),
                        reader.number("capacity.invites", 128, 1, 1000),
                        reader.number("capacity.storedMatches", 100, 1, 1000),
                        reader.number("capacity.historyMiB", 256, 8, 4096) * 1024L * 1024,
                        reader.number("capacity.tournamentPlayers", 32, 2, 64),
                        reader.number("capacity.participants", 32, 2, 64),
                        reader.number("capacity.ipKeys", 1024, 8, 16384));
        if (capacity.tournamentPlayers() > capacity.participants()) {
            throw new IllegalArgumentException("tournamentPlayers must not exceed participants");
        }
        if ((long) capacity.participants() * game.simulationHz() * game.roundSeconds() > 76800) {
            throw new IllegalArgumentException(
                    "participants * simulationHz * roundSeconds must not exceed 76800");
        }
        var live =
                new Live(
                        reader.bool("live.enabled", false),
                        reader.number("live.respawnMillis", 2000, 100, 30000),
                        reader.number("live.spawnProtectionMillis", 1500, 0, 10000),
                        reader.number("live.idleTimeoutSeconds", 15, 5, 300),
                        reader.number("live.historyFrames", 600, 20, 1200),
                        reader.number("live.scoreSaveSeconds", 5, 1, 60),
                        reader.bool("live.deliveryEnabled", false),
                        reader.bool("live.reconEnabled", false),
                        reader.number("live.cargoSpeedPercent", 100, 1, 100),
                        reader.number("live.visionRadius", 340, 100, 1000),
                        reader.number("live.reconSourceCount", 4, 2, 4),
                        reader.bool("live.controlZonesEnabled", false),
                        reader.bool("live.combatEnabled", false),
                        reader.number("live.teamCount", 4, 2, 4));
        if (live.combatEnabled()
                && (!live.enabled()
                        || !live.reconEnabled()
                        || live.deliveryEnabled()
                        || live.controlZonesEnabled())) {
            throw new IllegalArgumentException(
                    "Team combat requires live and personal vision, sufficient participant slots, and no cargo or control zones");
        }
        if (live.controlZonesEnabled() && (!live.reconEnabled() || live.reconSourceCount() != 4)) {
            throw new IllegalArgumentException("Control zones require recon and four sources");
        }
        if (live.reconEnabled()
                && !live.combatEnabled()
                && (!live.enabled() || !live.deliveryEnabled() || capacity.participants() != 12)) {
            throw new IllegalArgumentException(
                    "Recon mode requires live.enabled=true, live.deliveryEnabled=true and 12 participant slots");
        }
        if (live.reconEnabled()
                && (network.workers() < capacity.participants() + 4
                        || network.maxConnections() < capacity.participants() + 4)) {
            throw new IllegalArgumentException(
                    "Recon requires at least one waiting slot per participant and four HTTP slots for commands and administration");
        }
        int physicsPeriodMillis = (1000 + game.simulationHz() - 1) / game.simulationHz();
        if (live.reconEnabled() && game.periodMillis() != physicsPeriodMillis) {
            throw new IllegalArgumentException(
                    "Recon requires the physical step period to match game.simulationHz ("
                            + physicsPeriodMillis
                            + " ms). Set game.maxStepRate and game.deadlineMillis accordingly; current period is "
                            + game.periodMillis()
                            + " ms.");
        }
        if (live.enabled() && live.idleTimeoutSeconds() * 1000L < 2L * game.periodMillis()) {
            throw new IllegalArgumentException(
                    "live.idleTimeoutSeconds must cover at least two network windows (minimum "
                            + ((2L * game.periodMillis() + 999) / 1000)
                            + " seconds); increase the timeout or reduce game.deadlineMillis");
        }
        if ((long) capacity.participants() * live.historyFrames() > 38400) {
            throw new IllegalArgumentException(
                    "participants * live.historyFrames must not exceed 38400");
        }
        var directory =
                base.resolve(reader.text("storage.directory", "data-v2"))
                        .normalize()
                        .toAbsolutePath();
        reader.finish();
        return new ServerConfig(network, game, capacity, directory, live);
    }

    private static final class Reader {
        private final Properties p;
        private final Set<String> used = new HashSet<>();

        Reader(Properties p) {
            this.p = p;
        }

        String text(String key, String fallback) {
            used.add(key);
            return p.getProperty(key, fallback).trim();
        }

        int number(String key, int fallback, int min, int max) {
            try {
                var n = Integer.parseInt(text(key, Integer.toString(fallback)));
                if (n < min || n > max) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
        }

        boolean bool(String key, boolean fallback) {
            var value = text(key, Boolean.toString(fallback));
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException(key + " must be true or false");
            }
            return Boolean.parseBoolean(value);
        }

        void finish() {
            for (var key : p.stringPropertyNames()) {
                if (!used.contains(key)) {
                    throw new IllegalArgumentException("Unknown configuration key: " + key);
                }
            }
        }
    }
}

package edu.arena.protocol;

import edu.arena.core.bot.ControlTiming;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Placement;
import edu.arena.core.recon.ReconObservation;
import java.util.List;
import java.util.Map;

/** Version-two wire values. Domain behavior belongs to core or application services. */
public final class Messages {
    public static final String VERSION = "2.13";
    public static final String VERSION_HEADER = "X-Arena-Protocol";

    private Messages() {}

    public record Error(String code, String message) {}

    public record NameRequest(String name) {}

    public record Info(
            String version,
            int tickRate,
            int maxStepRate,
            int playerPollMillis,
            int spectatorPollMillis,
            int commandTimeoutMillis,
            List<String> maps,
            int maxParticipants,
            int lobbyWaitMillis,
            int controlPeriodTicks,
            boolean reconEnabled) {
        public Info(
                String version,
                int tickRate,
                int maxStepRate,
                int playerPollMillis,
                int spectatorPollMillis,
                int commandTimeoutMillis,
                List<String> maps,
                int maxParticipants,
                int lobbyWaitMillis,
                int controlPeriodTicks) {
            this(
                    version,
                    tickRate,
                    maxStepRate,
                    playerPollMillis,
                    spectatorPollMillis,
                    commandTimeoutMillis,
                    maps,
                    maxParticipants,
                    lobbyWaitMillis,
                    controlPeriodTicks,
                    false);
        }

        public Info(
                String version,
                int tickRate,
                int maxStepRate,
                int playerPollMillis,
                int spectatorPollMillis,
                int commandTimeoutMillis,
                List<String> maps,
                int maxParticipants,
                int lobbyWaitMillis) {
            this(
                    version,
                    tickRate,
                    maxStepRate,
                    playerPollMillis,
                    spectatorPollMillis,
                    commandTimeoutMillis,
                    maps,
                    maxParticipants,
                    lobbyWaitMillis,
                    Math.max(1, (tickRate + 3) / 4));
        }

        public Info {
            maps = List.copyOf(maps);
        }
    }

    public record Enroll(String password, String name) {
        @Override
        public String toString() {
            return "Enroll[password=<redacted>, name=" + name + "]";
        }
    }

    public record Enrollment(String playerId, String name, String token) {
        @Override
        public String toString() {
            return "Enrollment[playerId=" + playerId + ", name=" + name + ", token=<redacted>]";
        }
    }

    public record Me(
            String playerId,
            String name,
            String matchId,
            boolean queued,
            boolean revoked,
            boolean approved) {
        public Me(String playerId, String name, String matchId, boolean queued, boolean revoked) {
            this(playerId, name, matchId, queued, revoked, true);
        }
    }

    public record AdminPlayer(
            String id,
            String name,
            boolean revoked,
            boolean approved,
            long lastSeenEpochMillis,
            boolean online,
            boolean queued,
            String matchId) {}

    public record QueueRequest(String mode, String opponent, String map) {}

    public record QueueReply(boolean queued, String matchId) {}

    public record Command(int tick, Control control) {}

    public record CommandReceipt(
            int acceptedTick, int applyAtTick, int expiresAtTick, boolean duplicate) {
        public CommandReceipt(int acceptedTick) {
            this(acceptedTick, acceptedTick, acceptedTick + 1, false);
        }
    }

    public record MatchPlayer(String id, String name, int slot, boolean bot) {}

    public record LobbyView(
            List<MatchPlayer> waiting,
            String activeMatchId,
            long startsAtEpochMillis,
            int capacity,
            int trainingBots) {
        public LobbyView {
            waiting = List.copyOf(waiting);
        }
    }

    public record MatchView(
            String id,
            String status,
            ArenaMap map,
            GameRules rules,
            GameState state,
            List<MatchPlayer> players,
            long deadlineEpochMillis,
            String finishedReason,
            boolean ranked,
            List<Integer> missing,
            String tournamentId,
            List<Placement> placements,
            LiveView live,
            ControlTiming controlTiming,
            ReconView recon) {
        public MatchView(
                String id,
                String status,
                ArenaMap map,
                GameRules rules,
                GameState state,
                List<MatchPlayer> players,
                long deadlineEpochMillis,
                String finishedReason,
                boolean ranked,
                List<Integer> missing,
                String tournamentId,
                List<Placement> placements,
                LiveView live,
                ControlTiming controlTiming) {
            this(
                    id,
                    status,
                    map,
                    rules,
                    state,
                    players,
                    deadlineEpochMillis,
                    finishedReason,
                    ranked,
                    missing,
                    tournamentId,
                    placements,
                    live,
                    controlTiming,
                    null);
        }

        public MatchView(
                String id,
                String status,
                ArenaMap map,
                GameRules rules,
                GameState state,
                List<MatchPlayer> players,
                long deadlineEpochMillis,
                String finishedReason,
                boolean ranked,
                List<Integer> missing,
                String tournamentId,
                List<Placement> placements,
                LiveView live) {
            this(
                    id,
                    status,
                    map,
                    rules,
                    state,
                    players,
                    deadlineEpochMillis,
                    finishedReason,
                    ranked,
                    missing,
                    tournamentId,
                    placements,
                    live,
                    null);
        }

        public MatchView withControlTiming(ControlTiming timing) {
            return new MatchView(
                    id,
                    status,
                    map,
                    rules,
                    state,
                    players,
                    deadlineEpochMillis,
                    finishedReason,
                    ranked,
                    missing,
                    tournamentId,
                    placements,
                    live,
                    timing,
                    recon);
        }

        public MatchView withRecon(ReconView value) {
            return new MatchView(
                    id,
                    status,
                    map,
                    rules,
                    state,
                    players,
                    deadlineEpochMillis,
                    finishedReason,
                    ranked,
                    missing,
                    tournamentId,
                    placements,
                    live,
                    controlTiming,
                    value);
        }

        public MatchView {
            players = List.copyOf(players);
            missing = List.copyOf(missing);
            placements = List.copyOf(placements);
        }

        public MatchView(
                String id,
                String status,
                ArenaMap map,
                GameRules rules,
                GameState state,
                List<MatchPlayer> players,
                long deadlineEpochMillis,
                String finishedReason,
                boolean ranked,
                List<Integer> missing,
                String tournamentId,
                List<Placement> placements) {
            this(
                    id,
                    status,
                    map,
                    rules,
                    state,
                    players,
                    deadlineEpochMillis,
                    finishedReason,
                    ranked,
                    missing,
                    tournamentId,
                    placements,
                    null);
        }
    }

    /** Public rules and team totals; never contains unfiltered actor positions. */
    public record ReconView(
            int teams,
            int teamSize,
            double visionRadius,
            List<Integer> teamScores,
            double cargoSpeedFactor,
            String roundId,
            List<Integer> teamBySlot) {
        public ReconView {
            teamScores = List.copyOf(teamScores);
            teamBySlot = List.copyOf(teamBySlot);
        }
    }

    /** Authenticated personal snapshot. Full MatchView is deliberately absent. */
    public record ReconFrame(
            String matchId,
            String status,
            int respawnAtTick,
            ReconObservation observation,
            List<MatchPlayer> players) {
        public ReconFrame {
            players = List.copyOf(players);
        }
    }

    public record TeamAssignment(String playerId, int team) {}

    public record RoundStartRequest(String requestId, String expectedRoundId) {}

    public record RoundStartStatus(
            String requestId,
            String expectedRoundId,
            String roundId,
            int startTick,
            boolean applied) {}

    public record RoundStatus(String roundId, RoundStartStatus pending) {}

    /** Live membership and bounded event history; absent from archived finite rounds. */
    public record LiveView(
            List<LivePlayer> players,
            List<LiveEvent> events,
            int historyFromTick,
            int respawnTicks) {
        public LiveView {
            players = List.copyOf(players);
            events = List.copyOf(events);
            if (historyFromTick < 0 || respawnTicks < 1) {
                throw new IllegalArgumentException("Invalid live arena timing");
            }
        }
    }

    /** Life changes on each spawn, even if a client did not observe the death snapshot. */
    public record LivePlayer(
            String playerId,
            String name,
            int slot,
            long kills,
            long deaths,
            long points,
            int respawnAtTick,
            long life) {}

    /** Names are captured when the event occurs; later renames or slot reuse cannot rewrite it. */
    public record LiveEvent(
            long sequence,
            int tick,
            String reason,
            String victimId,
            String victimName,
            String actorId,
            String actorName) {}

    public record LiveStanding(
            String playerId, String name, long kills, long deaths, long points, int rank) {}

    public record MatchSummary(
            String id,
            String status,
            List<MatchPlayer> players,
            int tick,
            String outcome,
            boolean ranked,
            String map,
            String finishedReason) {
        public MatchSummary {
            players = List.copyOf(players);
        }

        public static MatchSummary of(MatchView m) {
            return new MatchSummary(
                    m.id(),
                    m.status(),
                    m.players(),
                    m.state().tick(),
                    m.state().outcome().name(),
                    m.ranked(),
                    m.map().id(),
                    m.finishedReason());
        }
    }

    /** Commands are released only after a turn has closed. */
    public record RecordedTurn(int tick, List<Control> controls, List<Boolean> missing) {
        public RecordedTurn {
            controls = List.copyOf(controls);
            missing = List.copyOf(missing);
        }
    }

    public record ReplayPage(
            MatchView match,
            int from,
            int total,
            List<GameState> frames,
            List<RecordedTurn> turns,
            List<RulesRevision> ruleChanges) {
        public ReplayPage {
            frames = List.copyOf(frames);
            turns = List.copyOf(turns);
            ruleChanges = List.copyOf(ruleChanges);
        }

        public ReplayPage(
                MatchView match,
                int from,
                int total,
                List<GameState> frames,
                List<RecordedTurn> turns) {
            this(match, from, total, frames, turns, List.of());
        }
    }

    /** Rules used for commands at fromTick and every later tick until the next revision. */
    public record RulesRevision(long revision, int fromTick, GameRules rules, int respawnTicks) {}

    /**
     * Teacher-controlled gameplay values. Durations are simulated milliseconds, rounded to ticks.
     */
    public record GameConditions(
            double moveSpeed,
            double turnSpeed,
            double turretSpeed,
            double bulletSpeed,
            double reloadMillis,
            double shieldDurationMillis,
            double shieldCooldownMillis,
            double respawnMillis) {}

    public record RuleLimit(double min, double max) {}

    public record RulesUpdate(long expectedRevision, GameConditions settings) {}

    public record RulesReset(long expectedRevision) {}

    /**
     * Session-only settings; revision protects concurrent teacher tabs from overwriting each other.
     */
    public record RulesControl(
            boolean enabled,
            long revision,
            long appliedRevision,
            int appliedAtTick,
            GameConditions current,
            GameConditions pending,
            Integer effectiveFromTick,
            GameConditions defaults,
            Map<String, RuleLimit> limits,
            int tickRate,
            boolean persistent) {
        public RulesControl {
            limits = Map.copyOf(limits);
        }
    }

    public record Standing(
            String playerId, String name, int rounds, int firstPlaces, int points, int rank) {}

    public record ServerPassword(String password) {
        @Override
        public String toString() {
            return "ServerPassword[password=<redacted>]";
        }
    }

    public record ServerAccess(boolean passwordConfigured) {}

    public record TournamentRequest(List<String> playerIds, String map, int rounds, long seed) {
        public TournamentRequest {
            playerIds = List.copyOf(playerIds);
        }
    }

    public record RoundView(int number, String status, String matchId, List<String> spawnOrder) {
        public RoundView {
            spawnOrder = List.copyOf(spawnOrder);
        }
    }

    public record TournamentView(
            String id,
            String status,
            int total,
            int completed,
            int active,
            int pending,
            List<RoundView> rounds) {
        public TournamentView {
            rounds = List.copyOf(rounds);
        }
    }
}

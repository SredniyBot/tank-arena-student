package edu.arena.server.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.arena.core.model.Placement;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages.MatchPlayer;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.RoundView;
import edu.arena.protocol.Messages.Standing;
import edu.arena.protocol.Messages.TournamentView;
import edu.arena.server.match.ApplicationFailure;
import edu.arena.server.persistence.AtomicFiles;
import edu.arena.server.persistence.MatchRepository;
import edu.arena.server.security.PlayerIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Durable common-round schedule and score ledger, independent of replay retention. */
public final class TournamentService {
    public record Round(
            int number,
            String status,
            String matchId,
            List<String> spawnOrder,
            List<Placement> placements) {
        public Round {
            spawnOrder = List.copyOf(spawnOrder);
            placements = List.copyOf(placements);
        }
    }

    public record Season(
            String id,
            String status,
            String map,
            long seed,
            List<PlayerIdentity> players,
            List<Round> rounds) {
        public Season {
            players = List.copyOf(players);
            rounds = List.copyOf(rounds);
        }
    }

    private final Path file;
    private final JsonCodec json;
    private Season season;

    public TournamentService(Path file, JsonCodec json, MatchRepository matches)
            throws IOException {
        this.file = file;
        this.json = json;
        if (!Files.exists(file)) {
            return;
        }
        if (Files.isSymbolicLink(file) || Files.size(file) > 2 * 1024 * 1024) {
            throw new IOException("Invalid tournament store");
        }
        try {
            var document = json.read(Files.readAllBytes(file), JsonNode.class);
            // Version 2.1 tournament ledgers predate explicit admission. The identity store remains
            // authoritative for future access; existing rosters and historical scores stay intact.
            var players = document.path("players");
            if (players.isArray()) {
                for (var player : players) {
                    if (player instanceof ObjectNode object && !object.has("approved")) {
                        object.put("approved", true);
                    }
                }
            }
            season = json.read(json.write(document), Season.class);
            validate(season);
            if (Set.of("RUNNING", "CANCELLED", "INTERRUPTED").contains(season.status())) {
                var recovered = new ArrayList<Round>();
                for (var round : season.rounds()) {
                    var persisted =
                            round.matchId().isEmpty()
                                    ? Optional.<MatchView>empty()
                                    : matches.view(round.matchId());
                    if (persisted.isPresent() && persisted.get().status().equals("FINISHED")) {
                        validateResult(round, persisted.get());
                        recovered.add(
                                new Round(
                                        round.number(),
                                        "COMPLETE",
                                        round.matchId(),
                                        round.spawnOrder(),
                                        persisted.get().placements()));
                    } else if (round.status().equals("COMPLETE")) {
                        recovered.add(round);
                    } else {
                        recovered.add(
                                new Round(
                                        round.number(),
                                        "CANCELLED",
                                        round.matchId(),
                                        round.spawnOrder(),
                                        List.of()));
                    }
                }
                String status = season.status().equals("RUNNING") ? "INTERRUPTED" : season.status();
                if (season.status().equals("RUNNING")
                        && recovered.stream().allMatch(r -> r.status().equals("COMPLETE"))) {
                    status = "COMPLETE";
                }
                commit(
                        new Season(
                                season.id(),
                                status,
                                season.map(),
                                season.seed(),
                                season.players(),
                                recovered));
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid tournament store", e);
        }
    }

    public void start(List<PlayerIdentity> players, String map, int rounds, long seed)
            throws IOException {
        if (running()) {
            throw new ApplicationFailure(
                    409, "TOURNAMENT_RUNNING", "Текущий турнир ещё не завершён.");
        }
        if (players.size() < 2
                || players.size() > 64
                || rounds < 1
                || rounds > 32
                || players.stream().map(PlayerIdentity::id).distinct().count() != players.size()) {
            throw new ApplicationFailure(
                    400, "INVALID_ROSTER", "Недопустимый состав или число раундов.");
        }
        var schedule = new ArrayList<Round>();
        for (int number = 1; number <= rounds; number++) {
            var order = new ArrayList<>(players.stream().map(PlayerIdentity::id).toList());
            Collections.shuffle(order, new Random(seed + number));
            schedule.add(new Round(number, "PENDING", "", order, List.of()));
        }
        commit(new Season(UUID.randomUUID().toString(), "RUNNING", map, seed, players, schedule));
    }

    public boolean running() {
        return season != null && season.status().equals("RUNNING");
    }

    public boolean contains(String id) {
        return running() && season.players().stream().anyMatch(p -> p.id().equals(id));
    }

    public Season season() {
        return season;
    }

    public List<Round> pending() {
        return running()
                ? season.rounds().stream().filter(r -> r.status().equals("PENDING")).toList()
                : List.of();
    }

    public void attach(int number, String matchId) throws IOException {
        var round = season.rounds().get(number - 1);
        if (!running() || !round.status().equals("PENDING")) {
            throw new IllegalStateException("Round is not pending");
        }
        replace(number, new Round(number, "RUNNING", matchId, round.spawnOrder(), List.of()));
    }

    public void complete(MatchView match) throws IOException {
        if (season == null || !match.tournamentId().equals(season.id())) {
            return;
        }
        var found =
                season.rounds().stream().filter(r -> r.matchId().equals(match.id())).findFirst();
        if (found.isEmpty() || found.get().status().equals("COMPLETE")) {
            return;
        }
        var round = found.get();
        validateResult(round, match);
        replace(
                round.number(),
                new Round(
                        round.number(),
                        "COMPLETE",
                        match.id(),
                        round.spawnOrder(),
                        match.placements()));
    }

    public void cancel() throws IOException {
        if (!running()) {
            return;
        }
        var rounds =
                season.rounds().stream()
                        .map(
                                r ->
                                        r.status().equals("COMPLETE")
                                                ? r
                                                : new Round(
                                                        r.number(),
                                                        "CANCELLED",
                                                        r.matchId(),
                                                        r.spawnOrder(),
                                                        List.of()))
                        .toList();
        commit(
                new Season(
                        season.id(),
                        "CANCELLED",
                        season.map(),
                        season.seed(),
                        season.players(),
                        rounds));
    }

    public TournamentView view() {
        if (season == null) {
            return null;
        }
        int complete = 0;
        int active = 0;
        int pending = 0;
        for (var r : season.rounds()) {
            if (r.status().equals("COMPLETE")) {
                complete++;
            }
            if (r.status().equals("RUNNING")) {
                active++;
            }
            if (r.status().equals("PENDING")) {
                pending++;
            }
        }
        return new TournamentView(
                season.id(),
                season.status(),
                season.rounds().size(),
                complete,
                active,
                pending,
                season.rounds().stream()
                        .map(
                                r ->
                                        new RoundView(
                                                r.number(),
                                                r.status(),
                                                r.matchId(),
                                                r.spawnOrder()))
                        .toList());
    }

    public List<Standing> standings() {
        if (season == null) {
            return List.of();
        }
        var stats = new HashMap<String, int[]>();
        season.players().forEach(p -> stats.put(p.id(), new int[3]));
        for (var r : season.rounds()) {
            if (r.status().equals("COMPLETE")) {
                for (var place : r.placements()) {
                    var row = stats.get(r.spawnOrder().get(place.slot()));
                    row[0]++;
                    if (place.rank() == 1) {
                        row[1]++;
                    }
                    row[2] += place.points();
                }
            }
        }
        var sorted = new ArrayList<Standing>();
        for (var player : season.players()) {
            var row = stats.get(player.id());
            sorted.add(new Standing(player.id(), player.name(), row[0], row[1], row[2], 0));
        }
        sorted.sort(
                Comparator.comparingInt(Standing::points)
                        .reversed()
                        .thenComparing(Standing::name)
                        .thenComparing(Standing::playerId));
        var result = new ArrayList<Standing>();
        int rank = 0;
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            var row = sorted.get(i);
            if (row.points() != previous) {
                rank = i + 1;
            }
            previous = row.points();
            result.add(
                    new Standing(
                            row.playerId(),
                            row.name(),
                            row.rounds(),
                            row.firstPlaces(),
                            row.points(),
                            rank));
        }
        return List.copyOf(result);
    }

    private void replace(int number, Round replacement) throws IOException {
        var rounds =
                season.rounds().stream().map(r -> r.number() == number ? replacement : r).toList();
        String status = season.status();
        if (status.equals("RUNNING")
                && rounds.stream().allMatch(r -> r.status().equals("COMPLETE"))) {
            status = "COMPLETE";
        }
        commit(
                new Season(
                        season.id(),
                        status,
                        season.map(),
                        season.seed(),
                        season.players(),
                        rounds));
    }

    private void validateResult(Round round, MatchView match) throws IOException {
        if (!match.status().equals("FINISHED")
                || !match.tournamentId().equals(season.id())
                || !match.players().stream()
                        .map(MatchPlayer::id)
                        .toList()
                        .equals(round.spawnOrder())
                || match.placements().size() != round.spawnOrder().size()) {
            throw new IOException("Round result does not match its roster");
        }
        for (int slot = 0; slot < match.placements().size(); slot++) {
            if (match.placements().get(slot).slot() != slot) {
                throw new IOException("Invalid placement slots");
            }
        }
    }

    private static void validate(Season value) throws IOException {
        if (value == null
                || value.players().size() < 2
                || value.players().size() > 64
                || value.rounds().isEmpty()
                || value.rounds().size() > 32
                || !Set.of("RUNNING", "COMPLETE", "CANCELLED", "INTERRUPTED")
                        .contains(value.status())) {
            throw new IOException("Invalid tournament bounds");
        }
        var ids = new HashSet<>(value.players().stream().map(PlayerIdentity::id).toList());
        if (ids.size() != value.players().size()) {
            throw new IOException("Duplicate tournament player");
        }
        for (int index = 0; index < value.rounds().size(); index++) {
            var r = value.rounds().get(index);
            if (r.number() != index + 1
                    || r.spawnOrder().size() != ids.size()
                    || !new HashSet<>(r.spawnOrder()).equals(ids)
                    || !Set.of("PENDING", "RUNNING", "COMPLETE", "CANCELLED").contains(r.status())
                    || (r.status().equals("COMPLETE") && r.placements().size() != ids.size())) {
                throw new IOException("Invalid round ledger");
            }
            for (int slot = 0; slot < r.placements().size(); slot++) {
                if (r.placements().get(slot).slot() != slot) {
                    throw new IOException("Invalid ledger slots");
                }
            }
        }
    }

    private void commit(Season next) throws IOException {
        validate(next);
        AtomicFiles.write(file, json.write(next));
        season = next;
    }
}

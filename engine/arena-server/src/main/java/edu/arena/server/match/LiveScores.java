package edu.arena.server.match;

import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages.LiveStanding;
import edu.arena.server.persistence.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Bounded cumulative scores. Mutations run under the coordinator lock; immutable snapshots are
 * written off-thread.
 */
final class LiveScores {
    record Score(String playerId, String name, long kills, long deaths, long deliveries) {
        Score(String playerId, String name, long kills, long deaths) {
            this(playerId, name, kills, deaths, 0);
        }
    }

    record Snapshot(int version, long revision, List<Score> players) {
        Snapshot {
            players = List.copyOf(players);
        }
    }

    private final Path file;
    private final int capacity;
    private final JsonCodec json = new JsonCodec();
    private final LinkedHashMap<String, Score> scores = new LinkedHashMap<>();
    private long revision;

    LiveScores(Path file, int capacity) throws IOException {
        this.file = file;
        this.capacity = capacity;
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid live scores file");
            }
            byte[] bytes;
            try (var in = Files.newInputStream(file)) {
                bytes = in.readNBytes(capacity * 512 + 1025);
            }
            if (bytes.length > capacity * 512 + 1024) {
                throw new IOException("Live scores exceed storage bound");
            }
            var loaded =
                    json.readMigrated(
                            bytes,
                            Snapshot.class,
                            root -> {
                                if (root.path("version").asInt() == 1
                                        && root.path("players").isArray()) {
                                    for (var player : root.path("players")) {
                                        if (player
                                                        instanceof
                                                        com.fasterxml.jackson.databind.node
                                                                                .ObjectNode
                                                                        row
                                                && !row.has("deliveries")) {
                                            row.put("deliveries", 0);
                                        }
                                    }
                                    root.put("version", 2);
                                }
                            });
            if (loaded == null
                    || loaded.version() != 2
                    || loaded.revision() < 0
                    || loaded.players().size() > capacity) {
                throw new IOException("Invalid live scores");
            }
            for (var s : loaded.players()) {
                if (s == null
                        || s.playerId() == null
                        || s.playerId().isBlank()
                        || s.playerId().length() > 64
                        || s.name() == null
                        || s.name().isBlank()
                        || s.name().length() > 128
                        || s.kills() < 0
                        || s.deaths() < 0
                        || s.deliveries() < 0
                        || scores.putIfAbsent(s.playerId(), s) != null) {
                    throw new IOException("Invalid live score row");
                }
            }
            revision = loaded.revision();
        }
    }

    void register(String id, String name) {
        var old = scores.get(id);
        if (old != null && old.name().equals(name)) {
            return;
        }
        if (old == null && scores.size() >= capacity) {
            throw new IllegalStateException("Live scores capacity reached");
        }
        scores.put(
                id,
                new Score(
                        id,
                        name,
                        old == null ? 0 : old.kills(),
                        old == null ? 0 : old.deaths(),
                        old == null ? 0 : old.deliveries()));
        revision = Math.addExact(revision, 1);
    }

    void death(String victimId, String killerId) {
        var victim = Objects.requireNonNull(scores.get(victimId));
        scores.put(
                victimId,
                new Score(
                        victim.playerId(),
                        victim.name(),
                        victim.kills(),
                        Math.addExact(victim.deaths(), 1),
                        victim.deliveries()));
        if (killerId != null && !killerId.equals(victimId)) {
            var killer = scores.get(killerId);
            if (killer != null) {
                scores.put(
                        killerId,
                        new Score(
                                killer.playerId(),
                                killer.name(),
                                Math.addExact(killer.kills(), 1),
                                killer.deaths(),
                                killer.deliveries()));
            }
        }
        revision = Math.addExact(revision, 1);
    }

    Score get(String id) {
        return scores.get(id);
    }

    void delivered(String playerId) {
        var old = Objects.requireNonNull(scores.get(playerId));
        scores.put(
                playerId,
                new Score(
                        old.playerId(),
                        old.name(),
                        old.kills(),
                        old.deaths(),
                        Math.addExact(old.deliveries(), 1)));
        revision = Math.addExact(revision, 1);
    }

    Snapshot snapshot() {
        return new Snapshot(2, revision, new ArrayList<>(scores.values()));
    }

    void save(Snapshot snapshot) throws IOException {
        AtomicFiles.write(file, json.write(snapshot));
    }

    List<LiveStanding> standings() {
        return standings(false);
    }

    List<LiveStanding> standings(boolean deliveryMode) {
        return standings(deliveryMode, false);
    }

    List<LiveStanding> standings(boolean deliveryMode, boolean independentCombat) {
        java.util.function.ToLongFunction<Score> points =
                independentCombat
                        ? score -> 100L * (score.kills() - score.deaths())
                        : deliveryMode ? Score::deliveries : Score::kills;
        var sorted =
                scores.values().stream()
                        .sorted(
                                Comparator.comparingLong(points)
                                        .reversed()
                                        .thenComparing(Score::playerId))
                        .toList();
        var result = new ArrayList<LiveStanding>();
        long previous = -1;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var s = sorted.get(i);
            if (i == 0 || points.applyAsLong(s) != previous) {
                rank = i + 1;
            }
            previous = points.applyAsLong(s);
            result.add(
                    new LiveStanding(
                            s.playerId(),
                            s.name(),
                            s.kills(),
                            s.deaths(),
                            points.applyAsLong(s),
                            rank));
        }
        return List.copyOf(result);
    }
}

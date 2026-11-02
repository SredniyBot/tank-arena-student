package edu.arena.server.persistence;

import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Outcome;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.RecordedTurn;
import edu.arena.protocol.Messages.ReplayPage;
import edu.arena.server.match.ApplicationFailure;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Atomic directory archives. A replay request inflates only its intersecting 64-frame chunks. */
public final class FileMatchRepository implements MatchRepository {
    static final int CHUNK_FRAMES = 64;
    static final int MAX_DECOMPRESSED = 16 * 1024 * 1024;
    private static final int MAX_INDEX = 2 * 1024 * 1024;

    public record ChunkInfo(int from, int count, long bytes, String sha256) {}

    public record Index(int version, MatchView match, int total, List<ChunkInfo> chunks) {
        public Index {
            chunks = List.copyOf(chunks);
        }
    }

    public record Chunk(List<GameState> frames, List<RecordedTurn> turns) {
        public Chunk {
            frames = List.copyOf(frames);
            turns = List.copyOf(turns);
        }
    }

    private record Entry(Index index, long bytes) {}

    private final Path directory;
    private final int maxCount;
    private final long maxBytes;
    private final JsonCodec json;
    private final Object writeLock = new Object();
    private final LinkedHashMap<String, Entry> metadata = new LinkedHashMap<>();
    private final Map<String, Integer> readers = new HashMap<>();
    private long bytes;

    public FileMatchRepository(Path directory, int maxCount, long maxBytes, JsonCodec json)
            throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.json = json;
        if (maxCount < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Invalid archive capacity");
        }
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) {
            throw new IOException("Symlink archive directory");
        }
        try (var files = Files.list(this.directory)) {
            var paths =
                    files.sorted(Comparator.comparingLong(FileMatchRepository::modified)).toList();
            if (paths.size() > maxCount * 2L + 8) {
                throw new IOException("Archive recovery bound exceeded");
            }
            for (var path : paths) {
                String name = path.getFileName().toString();
                if (name.startsWith(".staging-")) {
                    deleteTree(path);
                    continue;
                }
                try {
                    validateId(name);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Unknown archive entry", e);
                }
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
                    throw new IOException("Invalid replay directory");
                }
                var index = readIndex(path, name);
                long length = Files.size(path.resolve("metadata.json"));
                for (int part = 0; part < index.chunks().size(); part++) {
                    var chunkPath = chunkPath(path, part);
                    var info = index.chunks().get(part);
                    if (Files.isSymbolicLink(chunkPath)
                            || !Files.isRegularFile(chunkPath)
                            || Files.size(chunkPath) != info.bytes()) {
                        throw new IOException("Missing or changed replay chunk");
                    }
                    length = Math.addExact(length, info.bytes());
                }
                if (length > maxBytes) {
                    throw new IOException("Replay exceeds archive quota");
                }
                metadata.put(name, new Entry(index, length));
                bytes += length;
            }
        }
        trim();
    }

    @Override
    public void save(CompletedMatch record) throws IOException {
        synchronized (writeLock) {
            saveRecord(record);
        }
    }

    private void saveRecord(CompletedMatch record) throws IOException {
        String id = record.match().id();
        validateId(id);
        synchronized (this) {
            if (metadata.containsKey(id)) {
                return;
            }
        }
        if (record.frames().isEmpty()
                || record.frames().size() > record.match().rules().maxTicks() + 1
                || !record.match().status().equals("FINISHED")
                || record.match().state().outcome() != Outcome.FINISHED
                || !record.frames().getLast().equals(record.match().state())) {
            throw new IOException("Invalid completed replay");
        }
        int n = record.match().players().size();
        for (int frame = 0; frame < record.frames().size(); frame++) {
            if (record.frames().get(frame).tick() != frame
                    || record.frames().get(frame).tanks().size() != n) {
                throw new IOException("Noncontiguous replay frames");
            }
        }
        for (int turn = 0; turn < record.turns().size(); turn++) {
            var t = record.turns().get(turn);
            if (t.tick() != turn
                    || t.controls().size() != n
                    || t.missing().size() != n
                    || t.tick() >= record.frames().size()) {
                throw new IOException("Invalid recorded inputs");
            }
        }
        var staging = Files.createTempDirectory(directory, ".staging-");
        boolean published = false;
        try {
            var chunks = new ArrayList<ChunkInfo>();
            long length = 0;
            for (int from = 0; from < record.frames().size(); from += CHUNK_FRAMES) {
                int end = Math.min(record.frames().size(), from + CHUNK_FRAMES);
                int first = from;
                var chunk =
                        new Chunk(
                                record.frames().subList(from, end),
                                record.turns().stream()
                                        .filter(t -> t.tick() >= first && t.tick() < end)
                                        .toList());
                byte[] source = json.write(chunk);
                if (source.length > MAX_DECOMPRESSED) {
                    throw new IOException("Replay chunk exceeds decoded limit");
                }
                var buffer = new ByteArrayOutputStream();
                try (var zip = new GZIPOutputStream(buffer)) {
                    zip.write(source);
                }
                byte[] encoded = buffer.toByteArray();
                length += encoded.length;
                ensureSpace(length, encoded.length);
                AtomicFiles.write(chunkPath(staging, chunks.size()), encoded);
                chunks.add(new ChunkInfo(from, end - from, encoded.length, digest(encoded)));
            }
            var index = new Index(5, record.match(), record.frames().size(), chunks);
            byte[] indexData = json.write(index);
            if (indexData.length > MAX_INDEX) {
                throw new IOException("Replay index exceeds limit");
            }
            length += indexData.length;
            ensureSpace(length, indexData.length);
            AtomicFiles.write(staging.resolve("metadata.json"), indexData);
            var target = directory.resolve(id);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Replay destination already exists");
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            synchronized (this) {
                metadata.put(id, new Entry(index, length));
                bytes += length;
            }
            trim();
        } finally {
            if (!published) {
                deleteTree(staging);
            }
        }
    }

    @Override
    public Optional<ReplayPage> replay(String id, int from, int limit) throws IOException {
        validateId(id);
        Entry entry;
        synchronized (this) {
            entry = metadata.get(id);
            if (entry == null) {
                return Optional.empty();
            }
            readers.merge(id, 1, Integer::sum);
        }
        try {
            var index = entry.index();
            if (from < 0 || from > index.total() || limit < 1 || limit > 200) {
                throw new ApplicationFailure(
                        400, "INVALID_PAGE", "Страница повтора вне допустимого диапазона.");
            }
            int end = Math.min(index.total(), from + limit);
            var frames = new ArrayList<GameState>();
            var turns = new ArrayList<RecordedTurn>();
            for (int part = from / CHUNK_FRAMES; from < end && part * CHUNK_FRAMES < end; part++) {
                var info = index.chunks().get(part);
                var chunk =
                        readChunk(
                                directory.resolve(id),
                                part,
                                info,
                                index.match().players().size(),
                                index.version());
                frames.addAll(
                        chunk.frames()
                                .subList(
                                        Math.max(from, info.from()) - info.from(),
                                        Math.min(end, info.from() + info.count()) - info.from()));
                chunk.turns().stream()
                        .filter(t -> t.tick() >= from && t.tick() < end)
                        .forEach(turns::add);
            }
            return Optional.of(new ReplayPage(index.match(), from, index.total(), frames, turns));
        } finally {
            synchronized (this) {
                readers.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
                notifyAll();
            }
        }
    }

    @Override
    public synchronized Optional<MatchView> view(String id) {
        var entry = metadata.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.index().match());
    }

    @Override
    public synchronized List<MatchView> list() {
        var values =
                new ArrayList<>(metadata.values().stream().map(e -> e.index().match()).toList());
        Collections.reverse(values);
        return List.copyOf(values);
    }

    @Override
    public synchronized long storedBytes() {
        return bytes;
    }

    private Index readIndex(Path folder, String id) throws IOException {
        var path = folder.resolve("metadata.json");
        if (Files.isSymbolicLink(path) || Files.size(path) > MAX_INDEX) {
            throw new IOException("Invalid replay index");
        }
        try {
            var index =
                    json.readMigrated(Files.readAllBytes(path), Index.class, ReplaySchema::index);
            if (index == null
                    || (index.version() < 2 || index.version() > 5)
                    || !index.match().id().equals(id)
                    || !index.match().status().equals("FINISHED")
                    || index.match().state().outcome() != Outcome.FINISHED
                    || index.total() < 1
                    || index.total() > index.match().rules().maxTicks() + 1
                    || index.chunks().size() != (index.total() + CHUNK_FRAMES - 1) / CHUNK_FRAMES) {
                throw new IOException("Invalid replay index");
            }
            int participants = index.match().players().size();
            if (index.match().state().tick() != index.total() - 1
                    || participants != index.match().state().tanks().size()
                    || index.match().missing().size() != participants
                    || !new ArenaEngine(index.match().rules(), index.match().map())
                            .placements(index.match().state())
                            .equals(index.match().placements())) {
                throw new IOException("Inconsistent replay metadata");
            }
            for (int slot = 0; slot < participants; slot++) {
                if (index.match().players().get(slot).slot() != slot) {
                    throw new IOException("Invalid replay participant slots");
                }
            }
            for (int part = 0; part < index.chunks().size(); part++) {
                var chunk = index.chunks().get(part);
                if (chunk.from() != part * CHUNK_FRAMES
                        || chunk.count() != Math.min(CHUNK_FRAMES, index.total() - chunk.from())
                        || chunk.bytes() < 1
                        || chunk.bytes() > MAX_DECOMPRESSED + 65536L
                        || !chunk.sha256().matches("[a-f0-9]{64}")) {
                    throw new IOException("Invalid replay chunk index");
                }
            }
            return index;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid replay index", e);
        }
    }

    private Chunk readChunk(Path folder, int part, ChunkInfo info, int participants, int version)
            throws IOException {
        var path = chunkPath(folder, part);
        if (Files.isSymbolicLink(path) || Files.size(path) != info.bytes()) {
            throw new IOException("Changed replay chunk");
        }
        byte[] encoded = Files.readAllBytes(path);
        if (!digest(encoded).equals(info.sha256())) {
            throw new IOException("Replay chunk checksum mismatch");
        }
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            byte[] source = zip.readNBytes(MAX_DECOMPRESSED + 1);
            if (source.length > MAX_DECOMPRESSED) {
                throw new IOException("Replay decompression limit exceeded");
            }
            var chunk =
                    version >= 2 && version <= 4
                            ? json.readMigrated(
                                    source, Chunk.class, root -> ReplaySchema.chunk(root, version))
                            : json.read(source, Chunk.class);
            if (chunk == null || chunk.frames().size() != info.count()) {
                throw new IOException("Invalid replay chunk");
            }
            for (int offset = 0; offset < chunk.frames().size(); offset++) {
                if (chunk.frames().get(offset).tick() != info.from() + offset
                        || chunk.frames().get(offset).tanks().size() != participants) {
                    throw new IOException("Invalid frame sequence");
                }
            }
            int previous = info.from() - 1;
            for (var turn : chunk.turns()) {
                if (turn.tick() <= previous
                        || turn.tick() < info.from()
                        || turn.tick() >= info.from() + info.count()
                        || turn.controls().size() != participants
                        || turn.missing().size() != participants) {
                    throw new IOException("Invalid recorded turn");
                }
                previous = turn.tick();
            }
            return chunk;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid replay chunk", e);
        }
    }

    private void ensureSpace(long archiveBytes, int nextBytes) throws IOException {
        if (archiveBytes > maxBytes) {
            throw new IOException("Replay exceeds archive quota");
        }
        if (Files.getFileStore(directory).getUsableSpace() < nextBytes + 16L * 1024 * 1024) {
            throw new IOException("Insufficient disk reserve");
        }
    }

    private void trim() throws IOException {
        while (true) {
            String id;
            synchronized (this) {
                if (metadata.size() <= maxCount && bytes <= maxBytes) {
                    return;
                }
                id = metadata.keySet().iterator().next();
                if (readers.containsKey(id)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted replay retention", e);
                    }
                    continue;
                }
                bytes -= metadata.remove(id).bytes();
            }
            deleteTree(directory.resolve(id));
        }
    }

    private void deleteTree(Path target) throws IOException {
        var resolved = target.toAbsolutePath().normalize();
        if (!resolved.getParent().equals(directory)) {
            throw new IOException("Invalid archive cleanup target");
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(resolved)) {
            Files.delete(resolved);
            return;
        }
        try (var tree = Files.walk(resolved)) {
            for (var path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path chunkPath(Path folder, int part) {
        return folder.resolve(String.format(Locale.ROOT, "%05d.json.gz", part));
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateId(String id) {
        if (id == null
                || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid match id");
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

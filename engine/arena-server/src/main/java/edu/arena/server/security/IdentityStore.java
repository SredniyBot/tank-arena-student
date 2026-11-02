package edu.arena.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned, bounded private format. Checksum detects accidental corruption, not hostile local
 * writes.
 */
final class IdentityStore {
    private static final int MAGIC = 0x54414944;
    private static final int VERSION = 2;
    private final Path file;
    private final int maxPlayers;
    private final int maxInvites;

    IdentityStore(Path file, int maxPlayers, int maxInvites) {
        this.file = file;
        this.maxPlayers = maxPlayers;
        this.maxInvites = maxInvites;
    }

    State read() throws IOException {
        byte[] bytes = ProtectedFiles.readBounded(file, 128 + maxPlayers * 300 + maxInvites * 100);
        if (bytes.length < 48) {
            throw new IOException("Truncated identity store");
        }
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(
                SecretMaterial.digest(payload),
                Arrays.copyOfRange(bytes, payload.length, bytes.length))) {
            throw new IOException("Identity store checksum mismatch");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Unsupported identity store");
            }
            int version = in.readInt();
            if (version != 1 && version != VERSION) {
                throw new IOException("Unsupported identity store");
            }
            var players = new LinkedHashMap<String, StoredPlayer>();
            var tokenHashes = new java.util.HashSet<String>();
            int playerCount = count(in.readInt(), maxPlayers);
            for (int i = 0; i < playerCount; i++) {
                String id = string(in, 36);
                try {
                    if (!UUID.fromString(id).toString().equals(id)) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid stored identity", invalid);
                }
                String name = string(in, 128);
                try {
                    if (!IdentityService.validatedName(name).equals(name)) {
                        throw new IllegalArgumentException();
                    }
                } catch (RuntimeException invalid) {
                    throw new IOException("Invalid stored display name", invalid);
                }
                String hash = hash(in);
                int revoked = in.readUnsignedByte();
                int approved = version == 1 ? 1 : in.readUnsignedByte();
                if (revoked > 1
                        || approved > 1
                        || players.put(
                                        id,
                                        new StoredPlayer(
                                                id, name, hash, revoked == 1, approved == 1))
                                != null
                        || !tokenHashes.add(hash)) {
                    throw new IOException("Duplicate or invalid stored player");
                }
            }
            var invites = new LinkedHashMap<String, Long>();
            int inviteCount = count(in.readInt(), maxInvites);
            for (int i = 0; i < inviteCount; i++) {
                String hash = hash(in);
                long expires = in.readLong();
                if (expires <= 0 || invites.put(hash, expires) != null) {
                    throw new IOException("Invalid stored invitation");
                }
            }
            if (in.available() != 0) {
                throw new IOException("Unexpected identity store data");
            }
            ProtectedFiles.restrictToOwner(file);
            return new State(players, invites);
        }
    }

    void write(State state) throws IOException {
        count(state.players().size(), maxPlayers);
        count(state.invites().size(), maxInvites);
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(state.players().size());
            for (StoredPlayer player : state.players().values()) {
                string(out, player.id());
                string(out, player.name());
                string(out, player.tokenHash());
                out.writeBoolean(player.revoked());
                out.writeBoolean(player.approved());
            }
            out.writeInt(state.invites().size());
            for (var entry : state.invites().entrySet()) {
                string(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
        }
        byte[] payload = buffer.toByteArray();
        buffer.writeBytes(SecretMaterial.digest(payload));
        ProtectedFiles.writeAtomically(file, buffer.toByteArray());
    }

    private static int count(int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("Identity store exceeds configured capacity");
        }
        return count;
    }

    private static String hash(DataInputStream in) throws IOException {
        String hash = string(in, 64);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid stored credential hash");
        }
        return hash;
    }

    private static String string(DataInputStream in, int maximumBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maximumBytes) {
            throw new IOException("Stored field exceeds bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated identity field");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("Invalid identity text", invalid);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    record State(Map<String, StoredPlayer> players, Map<String, Long> invites) {}

    record StoredPlayer(
            String id, String name, String tokenHash, boolean revoked, boolean approved) {
        PlayerIdentity publicIdentity() {
            return new PlayerIdentity(id, name, revoked, approved);
        }
    }
}

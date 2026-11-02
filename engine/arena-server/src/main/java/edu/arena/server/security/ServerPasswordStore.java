package edu.arena.server.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Stores only a salted password hash in a bounded, private, atomically replaced file. */
final class ServerPasswordStore {
    private static final int MAGIC = 0x54415057;
    private static final int VERSION = 1;
    private static final int ITERATIONS = 600_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PAYLOAD_BYTES = 3 * Integer.BYTES + SALT_BYTES + HASH_BYTES;
    private static final int FILE_BYTES = PAYLOAD_BYTES + HASH_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;

    ServerPasswordStore(Path file) {
        this.file = file;
    }

    PasswordHash read() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        byte[] bytes = ProtectedFiles.readBounded(file, FILE_BYTES);
        if (bytes.length != FILE_BYTES) {
            throw new IOException("Invalid server password file length");
        }
        var payload = Arrays.copyOf(bytes, PAYLOAD_BYTES);
        if (!MessageDigest.isEqual(
                SecretMaterial.digest(payload),
                Arrays.copyOfRange(bytes, PAYLOAD_BYTES, FILE_BYTES))) {
            throw new IOException("Server password file checksum mismatch");
        }
        var data = ByteBuffer.wrap(payload);
        if (data.getInt() != MAGIC || data.getInt() != VERSION) {
            throw new IOException("Unsupported server password file");
        }
        int iterations = data.getInt();
        if (iterations < ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IOException("Server password hash iteration count is out of bounds");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] value = new byte[HASH_BYTES];
        data.get(salt);
        data.get(value);
        ProtectedFiles.restrictToOwner(file);
        return new PasswordHash(salt, value, iterations);
    }

    void write(PasswordHash password) throws IOException {
        byte[] payload =
                ByteBuffer.allocate(PAYLOAD_BYTES)
                        .putInt(MAGIC)
                        .putInt(VERSION)
                        .putInt(password.iterations)
                        .put(password.salt)
                        .put(password.value)
                        .array();
        byte[] bytes =
                ByteBuffer.allocate(FILE_BYTES)
                        .put(payload)
                        .put(SecretMaterial.digest(payload))
                        .array();
        ProtectedFiles.writeAtomically(file, bytes);
    }

    static PasswordHash hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new PasswordHash(salt, derive(password, salt, ITERATIONS), ITERATIONS);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        var specification = new PBEKeySpec(characters, salt, iterations, HASH_BYTES * Byte.SIZE);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "Java runtime does not provide PBKDF2WithHmacSHA256", unavailable);
        } finally {
            specification.clearPassword();
            Arrays.fill(characters, '\0');
        }
    }

    static final class PasswordHash {
        private final byte[] salt;
        private final byte[] value;
        private final int iterations;

        private PasswordHash(byte[] salt, byte[] value, int iterations) {
            this.salt = salt.clone();
            this.value = value.clone();
            this.iterations = iterations;
        }

        boolean matches(String password) {
            byte[] candidate = derive(password, salt, iterations);
            try {
                return MessageDigest.isEqual(value, candidate);
            } finally {
                Arrays.fill(candidate, (byte) 0);
            }
        }
    }
}

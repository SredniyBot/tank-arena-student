package edu.arena.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** A token file is a credential. It is never printed and never overwritten by registration. */
public final class TokenFiles {
    private TokenFiles() {}

    public static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Token file is missing or is not a regular file");
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes(129);
        }
        if (bytes.length > 128) {
            throw new InvalidTokenFileException("Token file is too large");
        }
        String token = new String(bytes, StandardCharsets.US_ASCII).strip();
        if (!valid(token)) {
            throw new InvalidTokenFileException("Invalid token file");
        }
        return token;
    }

    public static void writeNew(Path file, String token) throws IOException {
        if (!valid(token)) {
            throw new IOException("Server returned an invalid token");
        }
        Path target = file.toAbsolutePath().normalize();
        if (!Files.isDirectory(target.getParent())) {
            throw new IOException("Token file parent directory does not exist");
        }
        // CREATE_NEW prevents accidental replacement of an existing participant's credential.
        Files.createFile(target);
        try {
            restrict(target);
            try (var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap((token + "\n").getBytes(StandardCharsets.US_ASCII));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException error) {
            Files.deleteIfExists(target);
            throw error;
        }
    }

    private static boolean valid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    /** Distinguishes damaged contents from permission errors or unsafe filesystem entries. */
    static final class InvalidTokenFileException extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private InvalidTokenFileException(String message) {
            super(message);
        }
    }

    private static void restrict(Path file) throws IOException {
        var posix =
                Files.getFileAttributeView(
                        file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        var acl =
                Files.getFileAttributeView(
                        file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            acl.setAcl(
                    List.of(
                            AclEntry.newBuilder()
                                    .setType(AclEntryType.ALLOW)
                                    .setPrincipal(acl.getOwner())
                                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                    .build()));
            return;
        }
        throw new IOException("Filesystem cannot restrict token access to its owner");
    }
}

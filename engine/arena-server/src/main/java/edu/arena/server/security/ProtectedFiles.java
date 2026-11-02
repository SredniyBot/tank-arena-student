package edu.arena.server.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/**
 * Replaces whole files atomically. Unsupported atomic moves fail rather than risk partial writes.
 */
final class ProtectedFiles {
    private ProtectedFiles() {}

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing symbolic link for identity file");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".identity-", ".tmp");
        try {
            restrictToOwner(temporary);
            try (var channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static byte[] readBounded(Path file, int maximum) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid identity file");
        }
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IOException("Identity file exceeds configured capacity");
            }
            return bytes;
        }
    }

    static void restrictToOwner(Path file) throws IOException {
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
        throw new IOException("Filesystem cannot restrict access to credential files");
    }
}

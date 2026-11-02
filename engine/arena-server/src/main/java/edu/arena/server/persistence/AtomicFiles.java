package edu.arena.server.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Temp-file write + force + atomic rename; unsupported atomic moves fail closed. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        var temporary = Files.createTempFile(destination.getParent(), "write-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

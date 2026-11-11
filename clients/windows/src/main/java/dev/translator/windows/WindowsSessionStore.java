package dev.translator.windows;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Win32Exception;
import dev.translator.client.SavedSession;
import dev.translator.client.SessionStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;

final class WindowsSessionStore implements SessionStore, AutoCloseable {
    private final Path sessionFile;
    private final FileChannel channel;
    private final FileLock ownership;

    private WindowsSessionStore(Path lockFile, FileChannel channel, FileLock ownership) {
        this.sessionFile = sessionFile(lockFile);
        this.channel = channel;
        this.ownership = ownership;
    }

    static WindowsSessionStore open() throws IOException {
        String appData = System.getenv("LOCALAPPDATA");
        if (appData == null || appData.isBlank()) {
            throw new IOException("Local application data directory is unavailable");
        }
        return open(Path.of(appData, "Translator", "sessions"));
    }

    static WindowsSessionStore open(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            var candidates = files.filter(path -> path.getFileName().toString().endsWith(".lock"))
                    .sorted(Comparator.comparing(path -> !Files.exists(sessionFile(path))))
                    .toList();
            for (Path candidate : candidates) {
                WindowsSessionStore store = claim(candidate);
                if (store != null) {
                    return store;
                }
            }
        }
        WindowsSessionStore store = claim(directory.resolve(UUID.randomUUID() + ".lock"));
        if (store == null) {
            throw new IOException("Could not claim session storage");
        }
        return store;
    }

    private static WindowsSessionStore claim(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock ownership = channel.tryLock();
            if (ownership != null) {
                return new WindowsSessionStore(path, channel, ownership);
            }
        } catch (OverlappingFileLockException ignored) {
            // Another window in this JVM already owns the slot.
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
        channel.close();
        return null;
    }

    @Override
    public SavedSession load() throws IOException {
        if (!Files.exists(sessionFile)) {
            return null;
        }
        try {
            if (Files.size(sessionFile) > 65_536) {
                throw new IOException("Invalid saved session");
            }
            return SavedSession.decode(Crypt32Util.cryptUnprotectData(Files.readAllBytes(sessionFile)));
        } catch (Win32Exception error) {
            throw new IOException("Could not decrypt saved session", error);
        }
    }

    @Override
    public void save(SavedSession session) throws IOException {
        Path temporary = sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp");
        try {
            Files.write(temporary, Crypt32Util.cryptProtectData(session.encode()));
            Files.move(temporary, sessionFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Win32Exception error) {
            throw new IOException("Could not encrypt saved session", error);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void clear() throws IOException {
        Files.deleteIfExists(sessionFile);
    }

    @Override
    public void close() throws IOException {
        ownership.release();
        channel.close();
    }

    private static Path sessionFile(Path lockFile) {
        return lockFile.resolveSibling(lockFile.getFileName() + ".session");
    }
}

package dev.translator.windows;

import dev.translator.client.SavedSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledOnOs(OS.WINDOWS)
class WindowsSessionStoreTest {
    @TempDir
    Path directory;

    @Test
    void encryptsSessionsAndRestoresOnlyAnUnclaimedSlot() throws IOException {
        var firstSaved = new SavedSession("https://translator.example", "alice", "secret-refresh-one");
        var secondSaved = new SavedSession("https://translator.example", "alice", "secret-refresh-two");
        try (var second = WindowsSessionStore.open(directory)) {
            try (var first = WindowsSessionStore.open(directory)) {
                assertNull(first.load());
                assertNull(second.load());
                first.save(firstSaved);
                second.save(secondSaved);
                assertEquals(firstSaved, first.load());
                assertEquals(secondSaved, second.load());
            }
            try (var restarted = WindowsSessionStore.open(directory)) {
                assertEquals(firstSaved, restarted.load());
                restarted.clear();
                assertNull(restarted.load());
                assertEquals(secondSaved, second.load());
            }
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".session")).toList()) {
                    String contents = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertFalse(contents.contains("secret-refresh"));
                    assertFalse(contents.contains("translator.example"));
                }
            }
        }
    }
}

package dev.translator.client;

import java.io.IOException;

public interface SessionStore {
    SavedSession load() throws IOException;

    void save(SavedSession session) throws IOException;

    void clear() throws IOException;
}

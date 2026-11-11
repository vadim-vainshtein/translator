package dev.translator.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public record SavedSession(String serverUrl, String username, String refreshToken) {
    public byte[] encode() throws IOException {
        try {
            return new JSONObject()
                    .put("serverUrl", serverUrl)
                    .put("username", username)
                    .put("refreshToken", refreshToken)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Could not encode saved session", error);
        }
    }

    public static SavedSession decode(byte[] bytes) throws IOException {
        try {
            var json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String server = json.getString("serverUrl");
            String username = json.getString("username");
            String refresh = json.getString("refreshToken");
            if (server.isEmpty() || username.isEmpty() || refresh.isEmpty()) {
                throw new IOException("Invalid saved session");
            }
            return new SavedSession(server, username, refresh);
        } catch (JSONException error) {
            throw new IOException("Invalid saved session", error);
        }
    }

    @Override
    public String toString() {
        return "SavedSession[credentials redacted]";
    }
}

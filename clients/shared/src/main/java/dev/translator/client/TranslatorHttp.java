package dev.translator.client;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TranslatorHttp {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final URI server;

    TranslatorHttp(String serverUrl, TranslationSession.LocalHttp localHttp) {
        this.server = serverUri(serverUrl, localHttp);
    }

    Object request(String path, JSONObject body, String accessToken) throws IOException {
        var url = server.resolve(path).toURL();
        var connection = (HttpURLConnection) ("http".equals(url.getProtocol())
                ? url.openConnection(Proxy.NO_PROXY)
                : url.openConnection());
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        if (accessToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }

        try {
            if (body != null) {
                writeBody(connection, body);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ApiFailure(status, errorCode(connection.getErrorStream()));
            }
            return status == 204 ? null : readJson(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static void writeBody(HttpURLConnection connection, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
    }

    private static Object readJson(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                    throw new InvalidResponse();
                }
                bytes.write(chunk, 0, count);
            }
            JSONTokener json = new JSONTokener(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            Object value = json.nextValue();
            if (json.nextClean() != 0) {
                throw new InvalidResponse();
            }
            return value;
        } catch (JSONException error) {
            throw new InvalidResponse();
        }
    }

    private static String errorCode(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try {
            return readJson(stream) instanceof JSONObject body ? body.optString("code", "") : "";
        } catch (IOException error) {
            return "";
        }
    }

    private static URI serverUri(String input, TranslationSession.LocalHttp localHttp) {
        URI uri;
        try {
            uri = URI.create(input.trim());
        } catch (IllegalArgumentException error) {
            throw invalidServer();
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port == 0 || port > 65535 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw invalidServer();
        }

        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && allowedLocalHost(host, localHttp)) {
            return uri;
        }
        throw new IllegalArgumentException(
                "Укажите HTTPS-адрес сервера. HTTP доступен только для локальной разработки.");
    }

    private static boolean allowedLocalHost(String host, TranslationSession.LocalHttp localHttp) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return switch (localHttp) {
            case DISABLED -> false;
            case LOOPBACK -> "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                    || "[::1]".equals(normalized);
            case ANDROID_EMULATOR -> "10.0.2.2".equals(normalized);
        };
    }

    private static IllegalArgumentException invalidServer() {
        return new IllegalArgumentException(
                "Укажите адрес сервера без пути, например https://translator.example.");
    }

    static final class ApiFailure extends IOException {
        final int status;
        final String code;

        ApiFailure(int status, String code) {
            super("HTTP " + status);
            this.status = status;
            this.code = code;
        }
    }

    static final class InvalidResponse extends IOException {
        InvalidResponse() {
            super("Invalid translator response");
        }
    }
}

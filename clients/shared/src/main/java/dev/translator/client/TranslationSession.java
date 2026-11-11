package dev.translator.client;

import dev.translator.client.TranslatorHttp.ApiFailure;
import dev.translator.client.TranslatorHttp.InvalidResponse;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class TranslationSession {
    public enum LocalHttp { DISABLED, LOOPBACK, ANDROID_EMULATOR }

    public record Model(String id, String name, int maxInputBytes) {}

    public record Cost(BigDecimal totalCostUsd, long unpricedRequests) {}

    public record Translation(String text, String sourceLanguage, String targetLanguage, BigDecimal costUsd,
                              List<String> alternatives, List<Example> examples) {}

    public record Example(String sourceText, String translatedText) {}

    private record Tokens(String access, String refresh, long refreshAtNanos) {}

    private final TranslatorHttp http;
    private final String serverUrl;
    private final SessionStore store;
    private String username;
    private Tokens tokens;

    public TranslationSession(String serverUrl, LocalHttp localHttp) {
        this(serverUrl, localHttp, null);
    }

    public TranslationSession(String serverUrl, LocalHttp localHttp, SessionStore store) {
        this.http = new TranslatorHttp(serverUrl, localHttp);
        this.serverUrl = serverUrl;
        this.store = store;
    }

    public synchronized void login(String username, String password) throws IOException {
        if (tokens != null) {
            throw new IllegalStateException("A session is already active");
        }
        this.username = username;
        try {
            var body = new JSONObject().put("username", username).put("password", password);
            acceptTokens(http.request("/v1/auth/login", body, null));
        } catch (JSONException error) {
            throw new InvalidResponse();
        }
    }

    public synchronized void resume(SavedSession saved) throws IOException {
        if (tokens != null || !serverUrl.equals(saved.serverUrl())) {
            throw new IllegalStateException("Cannot restore this session");
        }
        username = saved.username();
        rotate(saved.refreshToken());
    }

    public synchronized boolean isSignedIn() {
        return tokens != null;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public synchronized String username() {
        return username;
    }

    public synchronized void logout() throws IOException {
        if (tokens == null) {
            return;
        }
        String refreshToken = tokens.refresh();
        tokens = null;
        IOException cleanupFailure = null;
        try {
            clearSavedSession();
        } catch (IOException error) {
            cleanupFailure = error;
        }
        try {
            http.request("/v1/auth/logout", refreshBody(refreshToken), null);
        } catch (IOException error) {
            throw new LogoutFailure(cleanupFailure, error);
        }
        if (cleanupFailure != null) {
            throw new LogoutFailure(cleanupFailure, null);
        }
    }

    public synchronized List<Model> getModels() throws IOException {
        Object response = authenticatedRequest("/v1/models", null);
        var models = new ArrayList<Model>();
        try {
            JSONArray array = array(response);
            for (int index = 0; index < array.length(); index++) {
                JSONObject model = array.getJSONObject(index);
                int maxInputBytes = number(model.get("maxInputBytes")).intValueExact();
                if (maxInputBytes <= 0) {
                    throw new InvalidResponse();
                }
                models.add(new Model(requiredString(model.get("id")), requiredString(model.get("name")),
                        maxInputBytes));
            }
        } catch (JSONException | ArithmeticException error) {
            throw new InvalidResponse();
        }
        return Collections.unmodifiableList(models);
    }

    public synchronized Cost getCost() throws IOException {
        JSONObject response = object(authenticatedRequest("/v1/translation-cost", null));
        try {
            BigDecimal total = number(response.get("totalCostUsd"));
            long unpricedRequests = number(response.get("unpricedRequests")).longValueExact();
            return new Cost(total, unpricedRequests);
        } catch (JSONException | ArithmeticException error) {
            throw new InvalidResponse();
        }
    }

    public synchronized Translation translate(String text, String sourceLanguage, String targetLanguage,
                                              String model, boolean includeUsage) throws IOException {
        if (text.length() > 10_000 || isBlank(text)) {
            throw new IllegalArgumentException("Введите текст для перевода длиной до 10 000 символов.");
        }
        try {
            var body = new JSONObject()
                    .put("text", text)
                    .put("targetLanguage", language(targetLanguage))
                    .put("model", model)
                    .put("includeUsage", includeUsage);
            if (sourceLanguage == null) {
                body.put("detectSourceLanguage", true);
            } else {
                body.put("sourceLanguage", language(sourceLanguage));
            }
            return readTranslation(object(authenticatedRequest("/v1/translations", body)));
        } catch (JSONException error) {
            throw new InvalidResponse();
        }
    }

    private Object authenticatedRequest(String path, JSONObject body) throws IOException {
        if (tokens == null) {
            throw new SessionEnded();
        }
        boolean refreshed = System.nanoTime() - tokens.refreshAtNanos() >= 0;
        if (refreshed) {
            refresh();
        }
        try {
            return http.request(path, body, tokens.access());
        } catch (ApiFailure error) {
            if (error.status != 401) {
                throw error;
            }
        }
        if (!refreshed) {
            refresh();
            try {
                return http.request(path, body, tokens.access());
            } catch (ApiFailure error) {
                if (error.status != 401) {
                    throw error;
                }
            }
        }
        tokens = null;
        clearSavedSession();
        throw new SessionEnded();
    }

    private void refresh() throws SessionEnded {
        rotate(tokens.refresh());
    }

    private void rotate(String refreshToken) throws SessionEnded {
        tokens = null;
        try {
            // Remove the single-use credential before sending it, including across process crashes.
            clearSavedSession();
            acceptTokens(http.request("/v1/auth/refresh", refreshBody(refreshToken), null));
        } catch (IOException error) {
            // The server may have consumed the old token even when the response was lost.
            throw new SessionEnded();
        }
    }

    private void acceptTokens(Object response) throws IOException {
        Tokens received = readTokens(response);
        if (store != null) {
            store.save(new SavedSession(serverUrl, username, received.refresh()));
        }
        tokens = received;
    }

    private void clearSavedSession() throws IOException {
        if (store != null) {
            store.clear();
        }
    }

    private static JSONObject refreshBody(String refreshToken) throws InvalidResponse {
        try {
            return new JSONObject().put("refreshToken", refreshToken);
        } catch (JSONException error) {
            throw new InvalidResponse();
        }
    }

    private static Tokens readTokens(Object response) throws InvalidResponse {
        try {
            JSONObject body = object(response);
            String accessToken = requiredString(body.get("accessToken"));
            String refreshToken = requiredString(body.get("refreshToken"));
            long expiresIn = number(body.get("expiresIn")).longValueExact();
            if (!accessToken.matches("[A-Za-z0-9._~-]+") || !"Bearer".equals(body.get("tokenType"))
                    || expiresIn < 1 || expiresIn > 3600) {
                throw new InvalidResponse();
            }
            long refreshAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, expiresIn - 5));
            return new Tokens(accessToken, refreshToken, refreshAt);
        } catch (JSONException | ArithmeticException error) {
            throw new InvalidResponse();
        }
    }

    private static Translation readTranslation(JSONObject body) throws JSONException, InvalidResponse {
        var alternatives = new ArrayList<String>();
        JSONArray alternativesJson = optionalArray(body, "alternatives");
        for (int index = 0; index < alternativesJson.length(); index++) {
            alternatives.add(requiredString(alternativesJson.get(index)));
        }

        var examples = new ArrayList<Example>();
        JSONArray examplesJson = optionalArray(body, "examples");
        for (int index = 0; index < examplesJson.length(); index++) {
            JSONObject example = examplesJson.getJSONObject(index);
            examples.add(new Example(requiredString(example.get("sourceText")),
                    requiredString(example.get("translatedText"))));
        }

        Object cost = body.get("costUsd");
        return new Translation(requiredString(body.get("translatedText")),
                requiredString(body.get("sourceLanguage")), requiredString(body.get("targetLanguage")),
                cost == JSONObject.NULL ? null : number(cost),
                Collections.unmodifiableList(alternatives), Collections.unmodifiableList(examples));
    }

    private static String language(String value) {
        String name = value.trim();
        if (name.length() > 80 || isBlank(name)) {
            throw new IllegalArgumentException("Введите код или название языка длиной до 80 символов.");
        }
        return name;
    }

    private static JSONObject object(Object value) throws InvalidResponse {
        if (value instanceof JSONObject body) {
            return body;
        }
        throw new InvalidResponse();
    }

    private static JSONArray array(Object value) throws InvalidResponse {
        if (value instanceof JSONArray items) {
            return items;
        }
        throw new InvalidResponse();
    }

    private static JSONArray optionalArray(JSONObject object, String key) throws InvalidResponse {
        return object.has(key) ? array(object.opt(key)) : new JSONArray();
    }

    private static String requiredString(Object value) throws InvalidResponse {
        if (value instanceof String text && !isBlank(text)) {
            return text;
        }
        throw new InvalidResponse();
    }

    private static boolean isBlank(String text) {
        return text.codePoints().allMatch(Character::isWhitespace);
    }

    private static BigDecimal number(Object value) throws InvalidResponse {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.signum() >= 0 && decimal.scale() >= -100 && decimal.scale() <= 100
                        && decimal.precision() <= 100) {
                    return decimal;
                }
            } catch (NumberFormatException error) {
                throw new InvalidResponse();
            }
        }
        throw new InvalidResponse();
    }

    static final class SessionEnded extends IOException {}

    public static final class LogoutFailure extends IOException {
        final boolean cleanupFailed;
        final boolean revocationFailed;

        private LogoutFailure(IOException cleanupFailure, IOException revocationFailure) {
            super("Could not complete logout",
                    revocationFailure == null ? cleanupFailure : revocationFailure);
            cleanupFailed = cleanupFailure != null;
            revocationFailed = revocationFailure != null;
        }
    }
}

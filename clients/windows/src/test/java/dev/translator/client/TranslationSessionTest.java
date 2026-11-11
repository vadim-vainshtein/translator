package dev.translator.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.translator.client.TranslationSession.LocalHttp;
import dev.translator.client.TranslationSession.LogoutFailure;
import dev.translator.client.TranslationSession.Model;
import dev.translator.client.TranslationSession.SessionEnded;
import dev.translator.client.TranslatorHttp.ApiFailure;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationSessionTest {
    private static final String MODEL_ID = "translation-fast";
    private static final String MODELS = """
            [{"id":"translation-fast","name":"Fast","provider":"openai","maxInputBytes":20000}]
            """;
    private static final String TRANSLATION = """
            {"requestId":"d5115ad6-15e2-4d8c-8aaa-509e36033112","translatedText":"банк",
             "sourceLanguage":"en","targetLanguage":"ru","model":"translation-fast","costUsd":0.000072,
             "alternatives":["берег"],"examples":[{"sourceText":"the bank","translatedText":"банк"}]}
            """;

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private TranslationSession session;

    @BeforeEach
    void startStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        session = new TranslationSession(address, LocalHttp.LOOPBACK);
    }

    @AfterEach
    void stopStandIn() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void translatesUsingTheServerContractAndLogsOut() throws IOException {
        replies.add(new Reply(200, tokens(1)));
        replies.add(new Reply(200, MODELS));
        replies.add(new Reply(200, TRANSLATION));
        replies.add(new Reply(200, "{\"totalCostUsd\":0.000072,\"unpricedRequests\":2}"));
        replies.add(new Reply(204, null));

        session.login("alice", "test-only-password");
        assertEquals(List.of(new Model(MODEL_ID, "Fast", 20_000)), session.getModels());
        var result = session.translate("  bank\n", null, "ru", MODEL_ID, true);
        assertEquals("банк", result.text());
        assertEquals("en", result.sourceLanguage());
        assertEquals("ru", result.targetLanguage());
        assertEquals(List.of("берег"), result.alternatives());
        assertEquals(List.of(new TranslationSession.Example("the bank", "банк")), result.examples());
        assertEquals(new BigDecimal("0.000072"), result.costUsd());
        assertEquals(new TranslationSession.Cost(new BigDecimal("0.000072"), 2), session.getCost());
        session.logout();
        assertFalse(session.isSignedIn());

        List<Request> sent = new ArrayList<>(requests);
        assertEquals(List.of("/v1/auth/login", "/v1/models", "/v1/translations", "/v1/translation-cost",
                "/v1/auth/logout"), sent.stream().map(Request::path).toList());
        assertEquals(List.of("POST", "GET", "POST", "GET", "POST"),
                sent.stream().map(Request::method).toList());
        assertNull(sent.getFirst().authorization());
        assertEquals("alice", new JSONObject(sent.getFirst().body()).getString("username"));
        assertEquals("Bearer access-1", sent.get(2).authorization());
        JSONObject payload = new JSONObject(sent.get(2).body());
        assertEquals("  bank\n", payload.getString("text"));
        assertEquals("ru", payload.getString("targetLanguage"));
        assertEquals(MODEL_ID, payload.getString("model"));
        assertTrue(payload.getBoolean("detectSourceLanguage"));
        assertTrue(payload.getBoolean("includeUsage"));
        assertFalse(payload.has("sourceLanguage"));
        assertNull(sent.getLast().authorization());
        assertEquals("refresh-1", new JSONObject(sent.getLast().body()).getString("refreshToken"));
    }

    @Test
    void refreshesAfterUnauthorizedAndUsesTheRotatedPair() throws IOException {
        replies.add(new Reply(200, tokens(1)));
        replies.add(new Reply(401, null));
        replies.add(new Reply(200, tokens(2)));
        replies.add(new Reply(200, MODELS));
        replies.add(new Reply(204, null));

        session.login("alice", "test-only-password");
        session.getModels();
        session.logout();

        List<Request> sent = new ArrayList<>(requests);
        assertEquals(List.of("/v1/auth/login", "/v1/models", "/v1/auth/refresh", "/v1/models",
                "/v1/auth/logout"),
                sent.stream().map(Request::path).toList());
        assertEquals("Bearer access-1", sent.get(1).authorization());
        assertNull(sent.get(2).authorization());
        assertEquals("refresh-1", new JSONObject(sent.get(2).body()).getString("refreshToken"));
        assertEquals("Bearer access-2", sent.get(3).authorization());
        assertEquals("refresh-2", new JSONObject(sent.get(4).body()).getString("refreshToken"));
    }

    @Test
    void discardsTheSessionWhenTheRefreshResponseIsLost() throws IOException {
        replies.add(new Reply(200, tokens(1)));
        replies.add(new Reply(401, null));
        replies.add(new Reply(0, null));

        session.login("alice", "test-only-password");
        assertThrows(SessionEnded.class, session::getModels);
        assertFalse(session.isSignedIn());
        assertThrows(SessionEnded.class, session::getModels);
        assertEquals(List.of("/v1/auth/login", "/v1/models", "/v1/auth/refresh"),
                requests.stream().map(Request::path).toList());
    }

    @Test
    void doesNotRepeatAPaidOperationAfterAnUpstreamFailure() throws IOException {
        replies.add(new Reply(200, tokens(1)));
        replies.add(new Reply(503, "{\"code\":\"PROVIDER_BUSY\"}"));

        session.login("alice", "test-only-password");
        var error = assertThrows(ApiFailure.class,
                () -> session.translate("bank", "en", "ru", MODEL_ID, false));
        assertEquals(503, error.status);
        assertEquals(List.of("/v1/auth/login", "/v1/translations"),
                requests.stream().map(Request::path).toList());
        JSONObject payload = new JSONObject(new ArrayList<>(requests).get(1).body());
        assertEquals("en", payload.getString("sourceLanguage"));
        assertFalse(payload.has("detectSourceLanguage"));
    }

    @Test
    void doesNotForwardCredentialsToARedirectTarget() {
        replies.add(new Reply(307, null));

        var error = assertThrows(ApiFailure.class, () -> session.login("alice", "test-only-password"));
        assertEquals(307, error.status);
        assertFalse(session.isSignedIn());
        assertEquals(List.of("/v1/auth/login"), requests.stream().map(Request::path).toList());
    }

    @Test
    void restrictsCleartextToTheSelectedDevelopmentEndpoint() {
        new TranslationSession("https://translator.example:8443/", LocalHttp.DISABLED);
        new TranslationSession("http://10.0.2.2:8080", LocalHttp.ANDROID_EMULATOR);
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationSession("http://10.0.2.2:8080", LocalHttp.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationSession("http://translator.example", LocalHttp.LOOPBACK));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationSession("https://alice@translator.example", LocalHttp.LOOPBACK));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationSession("https://translator.example/subpath", LocalHttp.LOOPBACK));
    }

    @Test
    void restoresTheLatestRefreshTokenAfterRelaunchAndClearsItOnLogout() throws IOException {
        var store = new MemoryStore();
        session = persistentSession(store);
        replies.add(new Reply(200, tokens(1)));
        session.login("alice", "test-only-password");
        assertEquals("alice", store.saved.username());
        assertEquals("refresh-1", store.saved.refreshToken());
        assertFalse(new String(store.saved.encode(), StandardCharsets.UTF_8).contains("test-only-password"));

        var restarted = persistentSession(store);
        replies.add(new Reply(200, tokens(2)));
        restarted.resume(store.load());
        assertTrue(restarted.isSignedIn());
        assertEquals("refresh-2", store.saved.refreshToken());
        replies.add(new Reply(200, MODELS));
        restarted.getModels();
        assertEquals("Bearer access-2", new ArrayList<>(requests).getLast().authorization());

        replies.add(new Reply(503, null));
        assertThrows(LogoutFailure.class, restarted::logout);
        assertNull(store.saved);
        assertFalse(restarted.isSignedIn());
    }

    @Test
    void removesTheSavedTokenBeforeRefreshAndNeverRestoresAnUncertainResult() throws IOException {
        var store = new MemoryStore();
        session = persistentSession(store);
        replies.add(new Reply(200, tokens(1)));
        session.login("alice", "test-only-password");

        var restarted = persistentSession(store);
        SavedSession saved = store.load();
        replies.add(new Reply(0, null));
        assertThrows(SessionEnded.class, () -> restarted.resume(saved));
        assertNull(store.saved);
        assertFalse(restarted.isSignedIn());
        assertEquals(1, store.clears);
        assertEquals(List.of("/v1/auth/login", "/v1/auth/refresh"),
                requests.stream().map(Request::path).toList());
    }

    @Test
    void rotatingAndEndingOneSessionPreservesAnotherSessionForTheSameAccount() throws IOException {
        var firstStore = new MemoryStore();
        var secondStore = new MemoryStore();
        var first = persistentSession(firstStore);
        var second = persistentSession(secondStore);
        replies.add(new Reply(200, tokens(1)));
        replies.add(new Reply(200, tokens(2)));
        first.login("alice", "test-only-password");
        second.login("alice", "test-only-password");

        replies.add(new Reply(401, null));
        replies.add(new Reply(200, tokens(3)));
        replies.add(new Reply(200, MODELS));
        first.getModels();
        assertEquals("refresh-3", firstStore.saved.refreshToken());
        assertEquals("refresh-2", secondStore.saved.refreshToken());
        replies.add(new Reply(204, null));
        first.logout();
        assertNull(firstStore.saved);
        assertEquals("refresh-2", secondStore.saved.refreshToken());
        assertTrue(second.isSignedIn());
    }

    private TranslationSession persistentSession(SessionStore store) {
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        return new TranslationSession(address, LocalHttp.LOOPBACK, store);
    }

    @Test
    void revokesTheServerSessionEvenWhenDeletingTheSavedCredentialFails() throws IOException {
        var store = new MemoryStore();
        session = persistentSession(store);
        replies.add(new Reply(200, tokens(1)));
        session.login("alice", "test-only-password");
        store.failClear = true;
        replies.add(new Reply(204, null));

        var failure = assertThrows(LogoutFailure.class, session::logout);
        assertFalse(session.isSignedIn());
        assertTrue(failure.cleanupFailed);
        assertFalse(failure.revocationFailed);
        assertEquals(List.of("/v1/auth/login", "/v1/auth/logout"),
                requests.stream().map(Request::path).toList());
    }

    private static final class MemoryStore implements SessionStore {
        private SavedSession saved;
        private int clears;
        private boolean failClear;

        @Override
        public SavedSession load() {
            return saved;
        }

        @Override
        public void save(SavedSession session) {
            saved = session;
        }

        @Override
        public void clear() throws IOException {
            if (failClear) {
                throw new IOException("Storage unavailable");
            }
            saved = null;
            clears++;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.poll();
            if (reply == null) {
                exchange.sendResponseHeaders(501, -1);
                return;
            }
            if (reply.status() == 0) {
                return;
            }
            if (reply.status() == 307) {
                exchange.getResponseHeaders().set("Location", "/unexpected");
            }
            if (reply.body() == null) {
                exchange.sendResponseHeaders(reply.status(), -1);
                return;
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static String tokens(int version) {
        return new JSONObject().put("accessToken", "access-" + version)
                .put("tokenType", "Bearer")
                .put("expiresIn", 900)
                .put("refreshToken", "refresh-" + version)
                .put("refreshExpiresIn", 3600)
                .toString();
    }

    private record Request(String method, String path, String authorization, String body) {}

    private record Reply(int status, String body) {}
}

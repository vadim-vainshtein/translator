package dev.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.quota.QuotaService;
import dev.translator.translation.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TranslatorIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    static final RSAKey key = key();
    static final AtomicInteger providerCalls = new AtomicInteger();
    static final AtomicReference<String> upstreamResponse = new AtomicReference<>();
    static final AtomicReference<String> capturedRequest = new AtomicReference<>();
    static final HttpServer upstream = server();
    static final String issuer = "https://identity.example.test/";
    static final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuotaService quota;
    @Autowired TranslatorProperties properties;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("translator.security.issuer", () -> issuer);
        registry.add("translator.security.audience", () -> "translator-api");
        registry.add("translator.security.jwk-set-uri", () -> base() + "/jwks");
        registry.add("translator.openai.base-url", () -> base() + "/v1/");
        registry.add("translator.openai.api-key", () -> "test-only-key");
        registry.add("translator.openai.timeout", () -> "1s");
        registry.add("translator.limits.requests-per-minute", () -> "3");
        registry.add("translator.models.translation-quality.required-scope", () -> "translate:premium");
    }

    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE translation_request, quota_account");
        providerCalls.set(0);
        capturedRequest.set(null);
        upstreamResponse.set(json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "status", "completed",
                "content", List.of(Map.of("type", "output_text", "text", "{\"translatedText\":\"Доброе утро!\"}")))),
                "usage", Map.of("input_tokens", 100, "output_tokens", 20))));
    }

    @AfterAll static void close() { upstream.stop(0); }

    @Test void translatesThroughRealHttpAndPersistsOnlyMetadata() throws Exception {
        var response = post(validToken("alice"), request());
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("translatedText").asText()).isEqualTo("Доброе утро!");
        assertThat(body.path("model").asText()).isEqualTo("translation-fast");
        var row = jdbc.queryForMap("SELECT * FROM translation_request WHERE request_id = ?", UUID.fromString(body.path("requestId").asText()));
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) row.get("charged_tokens")).longValue()).isEqualTo(120);
        assertThat(row.keySet()).doesNotContain("text", "translated_text", "source_text");
        assertThat(row.get("prompt_version")).isEqualTo("v1");
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    @Test void requiresValidSignedJwtWithAllMandatoryClaims() throws Exception {
        Instant validUntil = Instant.now().plusSeconds(300);
        var validClaims = SignedJWT.parse(validToken("alice")).getJWTClaimsSet();
        List<String> invalid = Arrays.asList(null, "not-a-jwt",
                token("alice", "wrong-api", validUntil, "translate:write", key),
                token("alice", "translator-api", Instant.now().minusSeconds(120), "translate:write", key),
                token("alice", "translator-api", null, "translate:write", key),
                token(null, "translator-api", validUntil, "translate:write", key),
                token("alice", "translator-api", validUntil, "translate:write", key()),
                sign(new JWTClaimsSet.Builder(validClaims).audience(List.of()).build(), key),
                sign(new JWTClaimsSet.Builder(validClaims).issuer("https://wrong.example/").build(), key),
                sign(new JWTClaimsSet.Builder(validClaims).notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build(), key));
        for (String accessToken : invalid) assertThat(post(accessToken, request()).statusCode()).isEqualTo(401);
        assertThat(providerCalls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM translation_request", Integer.class)).isZero();
    }

    @Test void enforcesScopeAndPerModelPermission() throws Exception {
        assertThat(post(token("alice", "translator-api", Instant.now().plusSeconds(300), "profile", key), request()).statusCode()).isEqualTo(403);
        assertThat(post(validToken("alice"), request().replace("translation-fast", "translation-quality")).statusCode()).isEqualTo(403);
        var models = get(validToken("alice"), "/v1/models");
        assertThat(models.statusCode()).isEqualTo(200);
        assertThat(models.body()).contains("translation-fast").doesNotContain("translation-quality");
        assertThat(providerCalls.get()).isZero();
    }

    @Test void rejectsInvalidRequestsBeforeProviderOrQuota() throws Exception {
        for (String body : List.of(request().replace("translation-fast", "unknown"), request().replace("\"en\"", "\"xx\""),
                request().replace("Good morning!", " "), request().replace("\"text\":", "\"unexpected\":\"x\",\"text\":"), "{}")) {
            assertThat(post(validToken("alice"), body).statusCode()).isEqualTo(400);
        }
        assertThat(post(validToken("alice"), request().replace("Good morning!", "x".repeat(70000))).statusCode()).isEqualTo(413);
        assertThat(post(validToken("alice"), request().replace("Good morning!", "界".repeat(7000))).statusCode()).isEqualTo(413);
        assertThat(providerCalls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM translation_request", Integer.class)).isZero();
    }

    @Test void rateLimitIsSharedByRequestsAndIsolatedBySubject() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post(validToken("alice"), request()).statusCode()).isEqualTo(200);
        var denied = post(validToken("alice"), request());
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.body()).contains("RATE_LIMIT");
        assertThat(post(validToken("bob"), request()).statusCode()).isEqualTo(200);
        assertThat(providerCalls.get()).isEqualTo(4);
    }

    @Test void failedProviderCallReleasesSlotButRetainsConservativeCharge() throws Exception {
        upstreamResponse.set("{\"status\":\"incomplete\",\"output\":[]}");
        var response = post(validToken("alice"), request());
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("INCOMPLETE_TRANSLATION");
        var row = jdbc.queryForMap("SELECT status, charged_tokens, error_code FROM translation_request");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("charged_tokens")).longValue()).isGreaterThan(4096);
        assertThat(row.get("error_code")).isEqualTo("INCOMPLETE_TRANSLATION");
    }

    @Test void concurrentReservationsAreAtomicAndExpiredLeasesReleaseSlots() throws Exception {
        var command = new TranslationCommand("Hello", "en", "ru");
        var model = properties.models().get("translation-fast");
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                start.await();
                try { quota.reserve("concurrent-account", "translation-fast", model, command); return true; }
                catch (ApiException exception) { assertThat(exception.code()).isEqualTo("CONCURRENCY_LIMIT"); return false; }
            }));
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(2);
        }
        jdbc.update("UPDATE translation_request SET lease_expires_at = clock_timestamp() - interval '1 second'");
        quota.reserve("concurrent-account", "translation-fast", model, command);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM translation_request WHERE status = 'EXPIRED'", Integer.class)).isEqualTo(2);
    }

    @Test void enforcesDailyReservationBudgetAndPreservesAdmissionDay() {
        var command = new TranslationCommand("Hello", "en", "ru");
        var model = properties.models().get("translation-fast");
        UUID id = quota.reserve("daily-account", "translation-fast", model, command);
        jdbc.update("UPDATE translation_request SET created_at = (date_trunc('day', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') - interval '1 second' WHERE request_id = ?", id);
        var admittedAt = jdbc.queryForObject("SELECT created_at FROM translation_request WHERE request_id = ?", OffsetDateTime.class, id);
        quota.success(id, new TranslationProvider.ProviderResult("x", 100L, 20L));
        assertThat(jdbc.queryForObject("SELECT created_at FROM translation_request WHERE request_id = ?", OffsetDateTime.class, id)).isEqualTo(admittedAt);
        assertThat(jdbc.queryForObject("SELECT charged_tokens FROM translation_request WHERE request_id = ?", Long.class, id)).isEqualTo(120);
        UUID today = quota.reserve("daily-account", "translation-fast", model, command);
        jdbc.update("UPDATE translation_request SET charged_tokens = ?, status = 'SUCCEEDED' WHERE request_id = ?", properties.limits().dailyTokens(), today);
        assertThatThrownBy(() -> quota.reserve("daily-account", "translation-fast", model, command))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("DAILY_QUOTA"));
    }

    private HttpResponse<String> post(String accessToken, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/translations"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String request() { return "{\"text\":\"Good morning!\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"ru\",\"model\":\"translation-fast\"}"; }
    private String validToken(String subject) throws Exception { return token(subject, "translator-api", Instant.now().plusSeconds(300), "translate:write", key); }
    private String token(String subject, String audience, Instant expires, String scope, RSAKey signingKey) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).issueTime(new Date()).claim("scope", scope);
        if (expires != null) builder.expirationTime(Date.from(expires));
        return sign(builder.build(), signingKey);
    }
    private String sign(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(signingKey));
        return signed.serialize();
    }
    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (JOSEException exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static String base() { return "http://127.0.0.1:" + upstream.getAddress().getPort(); }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] response = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var stream = exchange.getResponseBody()) { stream.write(response); }
            });
            server.createContext("/v1/responses", exchange -> {
                providerCalls.incrementAndGet();
                capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = upstreamResponse.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var stream = exchange.getResponseBody()) { stream.write(response); }
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}

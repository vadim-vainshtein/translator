package dev.translator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import dev.translator.api.ApiException;
import dev.translator.auth.AuthRepository;
import dev.translator.config.TranslatorProperties;
import dev.translator.quota.QuotaService;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationProvider;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TranslatorIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final RSAKey key = key();
    static final String privateKeyLocation = privateKeyLocation();
    static final AtomicInteger providerCalls = new AtomicInteger();
    static final AtomicReference<String> upstreamResponse = new AtomicReference<>();
    static final AtomicReference<String> capturedRequest = new AtomicReference<>();
    static final HttpServer upstream = server();
    static final String issuer = "translator";
    static final String adminPassword = "test-only-admin-password";
    static final String userPassword = "correct horse battery";
    static final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRepository authRepository;
    @Autowired QuotaService quota;
    @Autowired TranslatorProperties properties;
    @Autowired SpringLiquibase liquibase;

    @MockitoSpyBean
    PasswordEncoder passwords;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("translator.security.audience", () -> "translator-api");
        registry.add("ADMIN_PASSWORD", () -> adminPassword);
        registry.add("translator.security.private-key-location", () -> privateKeyLocation);
        registry.add("translator.openai.base-url", () -> base() + "/v1/");
        registry.add("translator.openai.api-key", () -> "test-only-key");
        registry.add("translator.openai.timeout", () -> "1s");
        registry.add("translator.limits.requests-per-minute", () -> "3");
        registry.add("translator.limits.global-concurrent-requests", () -> "3");
        registry.add("translator.limits.global-daily-tokens", () -> "300000");
        registry.add("translator.limits.body-timeout", () -> "500ms");
        registry.add("translator.limits.concurrent-bodies", () -> "2");
        registry.add("translator.models.translation-fast.model", () -> "gpt-4.1-mini");
        registry.add("server.tomcat.threads.max", () -> "2");
        registry.add("server.tomcat.threads.min-spare", () -> "2");
        registry.add(
                "translator.models.translation-quality.required-scope", () -> "translate:premium");
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute(
                "TRUNCATE translation_request, quota_account, auth_refresh_token, "
                        + "auth_refresh_family, auth_rate_limit");
        jdbc.update("DELETE FROM auth_user WHERE username <> 'admin'");
        providerCalls.set(0);
        capturedRequest.set(null);
        replyWithTranslation(
                Map.of("translatedText", "Доброе утро!"), Map.of("input_tokens", 100, "output_tokens", 20));
    }

    private void replyWithTranslation(Map<String, ?> translation, Map<String, Object> usage)
            throws Exception {
        var content = Map.of("type", "output_text", "text", json.writeValueAsString(translation));
        var message = Map.of("type", "message", "status", "completed", "content", List.of(content));
        upstreamResponse.set(json.writeValueAsString(
                Map.of("status", "completed", "output", List.of(message), "usage", usage)));
    }

    @AfterAll
    static void close() {
        upstream.stop(0);
    }

    @Test
    void liquibaseAppliesSchemaOnceAndPreservesDataOnRerun() throws Exception {
        var history =
                jdbc.queryForList(
                        "SELECT id, author, filename, dateexecuted, md5sum FROM databasechangelog "
                                + "ORDER BY orderexecuted");
        assertThat(history).hasSize(4);
        assertThat(history.getFirst().get("id")).isEqualTo("001-translation-metadata");
        assertThat(history.getFirst().get("author")).isEqualTo("translator");
        assertThat(history.getFirst().get("filename"))
                .isEqualTo("db/changelog/changes/001-translation-metadata.xml");
        assertThat(history.getLast().get("id")).isEqualTo("004-translation-cost");
        assertThat(jdbc.queryForObject("SELECT to_regclass('flyway_schema_history')", String.class))
                .isNull();
        jdbc.update("INSERT INTO quota_account(account_id) VALUES (?)", "migration-rerun-account");

        liquibase.afterPropertiesSet();

        assertThat(
                        jdbc.queryForList(
                                "SELECT id, author, filename, dateexecuted, md5sum FROM"
                                        + " databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT account_id FROM quota_account", String.class))
                .isEqualTo("migration-rerun-account");
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE tablename ="
                                        + " 'translation_request'",
                                String.class))
                .contains("translation_request_account_created", "translation_request_running");
    }

    @Test
    void translatesThroughRealHttpAndPersistsOnlyMetadata() throws Exception {
        var response = post(validToken("alice"), request());
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("translatedText").asText()).isEqualTo("Доброе утро!");
        assertThat(body.path("model").asText()).isEqualTo("translation-fast");
        assertThat(body.has("alternatives")).isFalse();
        assertThat(body.has("examples")).isFalse();
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM translation_request WHERE request_id = ?",
                        UUID.fromString(body.path("requestId").asText()));
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) row.get("charged_tokens")).longValue()).isEqualTo(120);
        assertThat(row.keySet()).doesNotContain("text", "translated_text", "source_text");
        assertThat(row.get("prompt_version")).isEqualTo("v2");
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    @Test
    void returnsDistinctTranslationsOfAnAmbiguousSentenceWithoutAnEmptyExamplesField() throws Exception {
        replyWithTranslation(
                Map.of("translatedText", "Я пошёл в банк.",
                        "alternatives", List.of("Я пошёл к берегу."), "examples", List.of()),
                Map.of("input_tokens", 100, "output_tokens", 20));
        var response = post(validToken("alice"), """
                {"text":"I went to the bank.","sourceLanguage":"en","targetLanguage":"ru",
                 "model":"translation-fast","includeUsage":true}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("translatedText").asText()).isEqualTo("Я пошёл в банк.");
        assertThat(body.path("alternatives").get(0).asText()).isEqualTo("Я пошёл к берегу.");
        assertThat(body.has("examples")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT prompt_version FROM translation_request WHERE request_id = ?",
                String.class,
                UUID.fromString(body.path("requestId").asText()))).isEqualTo("v3");
    }

    @Test
    void returnsUsageExamplesOfAShortExpressionSeparatelyFromTheTranslation() throws Exception {
        replyWithTranslation(
                Map.of("translatedText", "доброе утро", "alternatives", List.of(), "examples", List.of(
                        Map.of("sourceText", "Good morning, Maya!", "translatedText", "Доброе утро, Майя!"))),
                Map.of("input_tokens", 100, "output_tokens", 20));
        var response = post(validToken("alice"), """
                {"text":"good morning","sourceLanguage":"en","targetLanguage":"ru",
                 "model":"translation-fast","includeUsage":true}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("translatedText").asText()).isEqualTo("доброе утро");
        assertThat(body.has("alternatives")).isFalse();
        assertThat(body.at("/examples/0/sourceText").asText()).isEqualTo("Good morning, Maya!");
        assertThat(body.at("/examples/0/translatedText").asText()).isEqualTo("Доброе утро, Майя!");
    }

    @Test
    void returnsAndStoresTheDetectedLanguageWithACustomTarget() throws Exception {
        replyWithTranslation(
                Map.of("translatedText", "Goedemorgen!", "detectedSourceLanguage", "en"),
                Map.of("input_tokens", 100, "output_tokens", 20));
        var response = post(validToken("alice"), """
                {"text":"Good morning!","detectSourceLanguage":true,
                 "targetLanguage":"Нидерландский","model":"translation-fast"}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("sourceLanguage").asText()).isEqualTo("en");
        assertThat(body.path("targetLanguage").asText()).isEqualTo("Нидерландский");
        assertThat(body.path("translatedText").asText()).isEqualTo("Goedemorgen!");
        var row = jdbc.queryForMap(
                "SELECT source_language, target_language FROM translation_request WHERE request_id = ?",
                UUID.fromString(body.path("requestId").asText()));
        assertThat(row).containsEntry("source_language", "en")
                .containsEntry("target_language", "Нидерландский");
    }

    @Test
    void persistsCachedTokenCostAndOnlySummarizesTheCallersRequests() throws Exception {
        replyWithTranslation(Map.of("translatedText", "Доброе утро!"), Map.of(
                "input_tokens", 100,
                "input_tokens_details", Map.of("cached_tokens", 40),
                "output_tokens", 20));
        String alice = validToken("alice");
        var response = post(alice, request().replace("\"en\"", "\"Американский английский\""));
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("sourceLanguage").asText()).isEqualTo("Американский английский");
        var expected = new BigDecimal("0.000060");
        assertThat(body.path("costUsd").decimalValue()).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject(
                "SELECT cost_usd FROM translation_request WHERE request_id = ?",
                BigDecimal.class, UUID.fromString(body.path("requestId").asText())))
                .isEqualByComparingTo(expected);
        assertThat(post(validToken("bob"), request()).statusCode()).isEqualTo(200);

        replyWithTranslation(Map.of("translatedText", "Доброе утро!"), Map.of());
        var unknown = post(alice, request());
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(json.readTree(unknown.body()).path("costUsd").isNull()).isTrue();

        var summary = get(alice, "/v1/translation-cost");
        assertThat(summary.statusCode()).isEqualTo(200);
        var totals = json.readTree(summary.body());
        assertThat(totals.path("totalCostUsd").decimalValue()).isEqualByComparingTo(expected);
        assertThat(totals.path("unpricedRequests").asLong()).isEqualTo(1);
    }

    @Test
    void requiresValidSignedJwtWithAllMandatoryClaims() throws Exception {
        var invalid = invalidAccessTokens();
        for (String accessToken : invalid) {
            assertThat(post(accessToken, request()).statusCode()).isEqualTo(401);
        }
        assertThat(providerCalls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM translation_request", Integer.class))
                .isZero();
    }

    private List<String> invalidAccessTokens() throws Exception {
        Instant validUntil = Instant.now().plusSeconds(300);
        var validClaims = SignedJWT.parse(validToken("alice")).getJWTClaimsSet();
        List<String> invalid =
                Arrays.asList(
                        null,
                        "not-a-jwt",
                        token("alice", "wrong-api", validUntil, "translate:write", key),
                        token(
                                "alice",
                                "translator-api",
                                Instant.now().minusSeconds(120),
                                "translate:write",
                                key),
                        token("alice", "translator-api", null, "translate:write", key),
                        token(null, "translator-api", validUntil, "translate:write", key),
                        token(" ", "translator-api", validUntil, "translate:write", key),
                        token("alice", "translator-api", validUntil, "translate:write", key()),
                        sign(
                                new JWTClaimsSet.Builder(validClaims).audience(List.of()).build(),
                                key),
                        sign(
                                new JWTClaimsSet.Builder(validClaims)
                                        .issuer("https://wrong.example/")
                                        .build(),
                                key),
                        sign(
                                new JWTClaimsSet.Builder(validClaims)
                                        .notBeforeTime(Date.from(Instant.now().plusSeconds(300)))
                                        .build(),
                                key));
        return invalid;
    }

    @Test
    void enforcesScopeAndPerModelPermission() throws Exception {
        String insufficientScope =
                token("alice", "translator-api", Instant.now().plusSeconds(300), "profile", key);
        assertThat(post(insufficientScope, request()).statusCode()).isEqualTo(403);
        String premiumRequest = request().replace("translation-fast", "translation-quality");
        assertThat(post(validToken("alice"), premiumRequest).statusCode()).isEqualTo(403);
        var models = get(validToken("alice"), "/v1/models");
        assertThat(models.statusCode()).isEqualTo(200);
        assertThat(models.body())
                .contains("translation-fast", "gpt-4.1-mini")
                .doesNotContain("translation-quality");
        assertThat(providerCalls.get()).isZero();
    }

    @Test
    void rejectsInvalidRequestsBeforeProviderOrQuota() throws Exception {
        for (String body :
                List.of(
                        request().replace("translation-fast", "unknown"),
                        request().replace("\"en\"", "\"??\""),
                        request().replace("Good morning!", " "),
                        request().replace("\"text\":", "\"unexpected\":\"x\",\"text\":"),
                        "{}")) {
            assertThat(post(validToken("alice"), body).statusCode()).isEqualTo(400);
        }
        assertThat(
                        post(
                                        validToken("alice"),
                                        request().replace("Good morning!", "x".repeat(70000)))
                                .statusCode())
                .isEqualTo(413);
        assertThat(
                        post(
                                        validToken("alice"),
                                        request().replace("Good morning!", "界".repeat(7000)))
                                .statusCode())
                .isEqualTo(413);
        assertThat(providerCalls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM translation_request", Integer.class))
                .isZero();
    }

    @Test
    void rateLimitIsSharedByRequestsAndIsolatedBySubject() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post(validToken("alice"), request()).statusCode()).isEqualTo(200);
        }
        var denied = post(validToken("alice"), request());
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.body()).contains("RATE_LIMIT");
        assertThat(post(validToken("bob"), request()).statusCode()).isEqualTo(200);
        assertThat(providerCalls.get()).isEqualTo(4);
    }

    @Test
    void failedProviderCallReleasesSlotButRetainsConservativeCharge() throws Exception {
        upstreamResponse.set("""
                {"status":"incomplete","output":[],"usage":{"input_tokens":100,"output_tokens":20}}
                """);
        String alice = validToken("alice");
        var response = post(alice, request());
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("INCOMPLETE_TRANSLATION");
        var row =
                jdbc.queryForMap(
                        "SELECT status, charged_tokens, error_code, cost_usd FROM translation_request");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("charged_tokens")).longValue()).isGreaterThan(4096);
        assertThat(row.get("error_code")).isEqualTo("INCOMPLETE_TRANSLATION");
        var expected = new BigDecimal("0.000072");
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(expected);
        var summary = json.readTree(get(alice, "/v1/translation-cost").body());
        assertThat(summary.path("totalCostUsd").decimalValue()).isEqualByComparingTo(expected);
        assertThat(summary.path("unpricedRequests").asLong()).isZero();
    }

    @Test
    void concurrentReservationsAreAtomicAndExpiredLeasesReleaseSlots() throws Exception {
        var command = new TranslationCommand("Hello", "en", "ru");
        var model = properties.models().get("translation-fast");
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                results.add(
                        workers.submit(
                                () ->
                                        reserveAfter(
                                                start,
                                                "concurrent-account",
                                                model,
                                                command,
                                                "CONCURRENCY_LIMIT")));
            }
            start.countDown();
            assertThat(countAdmitted(results)).isEqualTo(2);
        }
        jdbc.update(
                "UPDATE translation_request SET lease_expires_at = "
                        + "clock_timestamp() - interval '1 second'");
        quota.reserve("concurrent-account", "translation-fast", model, command);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM translation_request WHERE status = 'EXPIRED'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void enforcesDailyReservationBudgetAndPreservesAdmissionDay() {
        var command = new TranslationCommand("Hello", "en", "ru");
        var model = properties.models().get("translation-fast");
        UUID id = quota.reserve("daily-account", "translation-fast", model, command);
        jdbc.update(
                "UPDATE translation_request SET created_at = (date_trunc('day', "
                        + "clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') - interval '1 "
                        + "second' WHERE request_id = ?",
                id);
        var admittedAt =
                jdbc.queryForObject(
                        "SELECT created_at FROM translation_request WHERE request_id = ?",
                        OffsetDateTime.class,
                        id);
        quota.success(id, new TranslationProvider.ProviderResult("x", 100L, 20L), null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_at FROM translation_request WHERE request_id = ?",
                                OffsetDateTime.class,
                                id))
                .isEqualTo(admittedAt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT charged_tokens FROM translation_request WHERE request_id ="
                                        + " ?",
                                Long.class,
                                id))
                .isEqualTo(120);
        UUID today = quota.reserve("daily-account", "translation-fast", model, command);
        jdbc.update(
                "UPDATE translation_request SET charged_tokens = ?, status = 'SUCCEEDED' "
                        + "WHERE request_id = ?",
                properties.limits().dailyTokens(),
                today);
        assertThatThrownBy(() -> quota.reserve("daily-account", "translation-fast", model, command))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DAILY_QUOTA"));
    }

    @Test
    void registersAndLogsInWithSelfIssuedJwtAndHashedCredentials() throws Exception {
        assertThat(properties.security().issuer()).isEqualTo("translator");
        var registered = register(credentials("Alice", userPassword));
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(registered.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store");
        assertThat(registered.body()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_family", Integer.class))
                .isZero();
        var tokens = login("alice", userPassword);
        var claims = SignedJWT.parse(tokens.path("accessToken").asText()).getJWTClaimsSet();
        var user = jdbc.queryForMap("SELECT * FROM auth_user WHERE username = 'alice'");
        assertRegisteredIdentity(tokens, claims, user);
        assertIssuedTokenPermissions(tokens, claims);
        assertLoginAndCredentialFailures(claims);
    }

    @Test
    void onlyBootstrappedAdminCanCreateUsersEvenAfterRefreshing() throws Exception {
        String alice = credentials("Alice", userPassword);
        assertThat(auth("register", alice).statusCode()).isEqualTo(401);
        assertThat(auth("login", credentials("admin", userPassword)).statusCode()).isEqualTo(401);

        var admin = login("ADMIN", adminPassword);
        String adminAccessToken = admin.path("accessToken").asText();
        assertThat(auth("register", alice, adminAccessToken).statusCode()).isEqualTo(201);
        assertThat(auth("register", credentials("AdMiN", userPassword), adminAccessToken).statusCode())
                .isEqualTo(409);

        String bob = credentials("bob", userPassword);
        var ordinary = login("alice", userPassword);
        assertThat(auth("register", bob, ordinary.path("accessToken").asText()).statusCode())
                .isEqualTo(403);
        var ordinaryRefreshed = refresh(ordinary);
        assertThat(auth("register", bob, ordinaryRefreshed.path("accessToken").asText()).statusCode())
                .isEqualTo(403);

        var adminRefreshed = refresh(admin);
        assertThat(auth("register", bob, adminRefreshed.path("accessToken").asText()).statusCode())
                .isEqualTo(201);
        assertThat(login("bob", userPassword).path("accessToken").asText()).isNotBlank();
    }

    @Test
    void accountInsertHandlesConflictsOnEitherUniqueIdentity() {
        UUID adminId =
                jdbc.queryForObject("SELECT user_id FROM auth_user WHERE username = 'admin'", UUID.class);
        assertThat(authRepository.createUser(adminId, "another_name", "unused-hash")).isFalse();
        assertThat(authRepository.createUser(UUID.randomUUID(), "admin", "unused-hash")).isFalse();
        assertThat(jdbc.queryForList("SELECT username FROM auth_user", String.class))
                .containsExactly("admin");
    }

    @Test
    void validatesAuthBodiesAndBoundsBeforeCreatingAccounts() throws Exception {
        for (String invalid :
                List.of(
                        "{}",
                        "{\"username\":\"alice\"}",
                        credentials("a", "correct horse battery"),
                        credentials("alice", "short"),
                        credentials("alice", "界".repeat(25)),
                        "{\"username\":\"alice\",\"password\":\"correct horse battery\","
                                + "\"scope\":\"translate:premium\"}")) {
            assertThat(register(invalid).statusCode()).isEqualTo(400);
        }
        assertThat(register(credentials("alice", "x".repeat(70000))).statusCode())
                .isEqualTo(413);
        for (String route : List.of("refresh", "logout")) {
            for (String body :
                    List.of(
                            "{}",
                            "{\"refreshToken\":null}",
                            "{\"refreshToken\":\"\"}",
                            "{\"refreshToken\":\"invalid\"}")) {
                assertThat(auth(route, body).statusCode()).isEqualTo(400);
            }
        }
        assertThat(jdbc.queryForList("SELECT username FROM auth_user", String.class))
                .containsExactly("admin");
        assertThat(providerCalls.get()).isZero();
    }

    @Test
    void passwordChangeReplacesCredentialsAndRevokesEveryRefreshSessionForThatAccount()
            throws Exception {
        var firstSession = createUserAndLogin("alice");
        var secondSession = refresh(login("alice", userPassword));
        var otherAccount = createUserAndLogin("bob");
        String firstAccessToken = firstSession.path("accessToken").asText();
        String newPassword = "界".repeat(24);

        var changed = auth(
                "change-password", passwordChangeBody(userPassword, newPassword), firstAccessToken);
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(get(firstAccessToken, "/v1/models").statusCode()).isEqualTo(200);
        assertThat(auth("login", credentials("alice", userPassword)).statusCode()).isEqualTo(401);
        for (var oldSession : List.of(firstSession, secondSession)) {
            String body = refreshBody(oldSession.path("refreshToken").asText());
            assertThat(auth("refresh", body).statusCode()).isEqualTo(401);
        }

        var newSession = login("alice", newPassword);
        var newClaims = SignedJWT.parse(newSession.path("accessToken").asText()).getJWTClaimsSet();
        assertThat(newClaims.getSubject())
                .isEqualTo(SignedJWT.parse(firstAccessToken).getJWTClaimsSet().getSubject());
        refresh(newSession);
        refresh(otherAccount);
        login("bob", userPassword);
    }

    @Test
    void passwordChangeValidatesTheLocalAccountAndBothPasswords() throws Exception {
        var session = createUserAndLogin("alice");
        String accessToken = session.path("accessToken").asText();
        String newPassword = "another long password";
        String validBody = passwordChangeBody(userPassword, newPassword);
        for (String subject : List.of("external-user", UUID.randomUUID().toString())) {
            var rejected = auth("change-password", validBody, validToken(subject));
            assertThat(rejected.statusCode()).isEqualTo(403);
            assertThat(json.readTree(rejected.body()).path("code").asText())
                    .isEqualTo("LOCAL_ACCOUNT_REQUIRED");
        }
        for (String invalid : List.of(
                "{}",
                passwordChangeBody("short", newPassword),
                passwordChangeBody(userPassword, "short"),
                passwordChangeBody("界".repeat(25), newPassword),
                passwordChangeBody(userPassword, "界".repeat(25)))) {
            assertThat(auth("change-password", invalid, accessToken).statusCode()).isEqualTo(400);
        }

        login("alice", userPassword);
        refresh(session);
    }

    @Test
    void rejectedPasswordChangesCountAgainstAnIndependentAccountLimit() throws Exception {
        var session = createUserAndLogin("alice");
        String accessToken = session.path("accessToken").asText();
        String newPassword = "another long password";
        var rejected = auth(
                "change-password", passwordChangeBody("incorrect password", newPassword), accessToken);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(json.readTree(rejected.body()).path("code").asText())
                .isEqualTo("INVALID_CURRENT_PASSWORD");

        String subject = SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        int updated = jdbc.update(
                "UPDATE auth_rate_limit SET attempts = ? WHERE client_hash = ?",
                properties.security().authRequestsPerMinute(),
                sha256("change-password:" + subject));
        assertThat(updated).isOne();
        var throttled = auth(
                "change-password", passwordChangeBody(userPassword, newPassword), accessToken);
        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(json.readTree(throttled.body()).path("code").asText()).isEqualTo("AUTH_RATE_LIMIT");

        login("alice", userPassword);
        refresh(session);
        var otherAccount = createUserAndLogin("bob");
        assertThat(auth(
                        "change-password",
                        passwordChangeBody(userPassword, newPassword),
                        otherAccount.path("accessToken").asText())
                .statusCode()).isEqualTo(204);
    }

    @Test
    void changingToTheCurrentPasswordKeepsTheExistingRefreshSession() throws Exception {
        var session = createUserAndLogin("alice");
        String existingHash = authRepository.findUser("alice").passwordHash();
        var rejected = auth(
                "change-password",
                passwordChangeBody(userPassword, userPassword),
                session.path("accessToken").asText());

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("PASSWORD_UNCHANGED");
        assertThat(authRepository.findUser("alice").passwordHash()).isEqualTo(existingHash);
        refresh(session);
    }

    @Test
    void aConcurrentLoginWithTheOldPasswordCannotLeaveAnActiveRefreshSession() throws Exception {
        var session = createUserAndLogin("alice");
        String accessToken = session.path("accessToken").asText();
        String changeBody = passwordChangeBody(userPassword, "another long password");
        String loginBody = credentials("alice", userPassword);
        var loginVerified = new CountDownLatch(1);
        var passwordChanged = new CountDownLatch(1);

        // Pause the login after BCrypt has accepted the old password.
        doAnswer(invocation -> {
            boolean matches = (boolean) invocation.callRealMethod();
            if (loginVerified.getCount() != 0) {
                assertThat(matches).isTrue();
                loginVerified.countDown();
                assertThat(passwordChanged.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return matches;
        }).when(passwords).matches(eq(userPassword), anyString());

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var loggingIn = workers.submit(() -> auth("login", loginBody));
            try {
                assertThat(loginVerified.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(auth("change-password", changeBody, accessToken).statusCode()).isEqualTo(204);
            } finally {
                passwordChanged.countDown();
            }
            assertThat(loggingIn.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_refresh_family WHERE revoked_at IS NULL", Integer.class))
                .isZero();
    }

    @Test
    void rotatesRefreshTokensAndReplayRevokesOnlyTheAffectedFamily() throws Exception {
        var first = createUserAndLogin("alice");
        var independent =
                json.readTree(auth("login", credentials("alice", "correct horse battery")).body());
        var expires =
                jdbc.queryForList(
                        "SELECT family_id, expires_at FROM auth_refresh_family ORDER BY family_id");
        String original = first.path("refreshToken").asText();
        var refreshed = auth("refresh", refreshBody(original));
        assertThat(refreshed.statusCode()).isEqualTo(200);
        var rotated = json.readTree(refreshed.body());
        assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(original);
        assertThat(rotated.path("accessToken").asText())
                .isNotEqualTo(first.path("accessToken").asText());
        assertThat(
                        jdbc.queryForList(
                                "SELECT family_id, expires_at FROM auth_refresh_family ORDER BY"
                                        + " family_id"))
                .isEqualTo(expires);
        assertThat(auth("refresh", refreshBody(original)).statusCode()).isEqualTo(401);
        assertThat(auth("refresh", refreshBody(rotated.path("refreshToken").asText())).statusCode())
                .isEqualTo(401);
        assertThat(
                        auth("refresh", refreshBody(independent.path("refreshToken").asText()))
                                .statusCode())
                .isEqualTo(200);
        // Access tokens are stateless and remain valid until their own expiration.
        assertThat(get(rotated.path("accessToken").asText(), "/v1/models").statusCode())
                .isEqualTo(200);
    }

    @Test
    void concurrentRefreshHasOneWinnerAndReplayRevokesTheWinner() throws Exception {
        var initial = createUserAndLogin("alice");
        String body = refreshBody(initial.path("refreshToken").asText());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                calls.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return auth("refresh", body);
                                }));
            start.countDown();
            var responses =
                    List.of(
                            calls.get(0).get(15, TimeUnit.SECONDS),
                            calls.get(1).get(15, TimeUnit.SECONDS));
            assertThat(responses)
                    .extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 401);
            var winner =
                    json.readTree(
                            responses.stream()
                                    .filter(r -> r.statusCode() == 200)
                                    .findFirst()
                                    .orElseThrow()
                                    .body());
            assertThat(
                            auth("refresh", refreshBody(winner.path("refreshToken").asText()))
                                    .statusCode())
                    .isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_refresh_family WHERE revoked_at IS"
                                            + " NOT NULL",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void logoutRevokesWholeFamilyAndExpiredOrUnknownRefreshTokensFail() throws Exception {
        var initial = createUserAndLogin("alice");
        var rotated =
                json.readTree(
                        auth("refresh", refreshBody(initial.path("refreshToken").asText())).body());
        // Logout accepts even an already consumed member and does not need an access token.
        assertThat(auth("logout", refreshBody(initial.path("refreshToken").asText())).statusCode())
                .isEqualTo(204);
        assertThat(auth("logout", refreshBody(initial.path("refreshToken").asText())).statusCode())
                .isEqualTo(204);
        assertThat(auth("refresh", refreshBody(rotated.path("refreshToken").asText())).statusCode())
                .isEqualTo(401);
        var login =
                json.readTree(auth("login", credentials("alice", "correct horse battery")).body());
        jdbc.update(
                "UPDATE auth_refresh_family SET expires_at = clock_timestamp() - interval '1"
                        + " second'");
        assertThat(auth("refresh", refreshBody(login.path("refreshToken").asText())).statusCode())
                .isEqualTo(401);
        assertThat(auth("refresh", refreshBody("a".repeat(43))).statusCode()).isEqualTo(401);
        assertThat(auth("logout", refreshBody("a".repeat(43))).statusCode()).isEqualTo(204);
    }

    @Test
    void authRateLimitCountsFailuresAndResetsAfterWindow() throws Exception {
        assertThat(auth("login", credentials("unknown", "correct horse battery")).statusCode())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limit", Integer.class))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE auth_rate_limit SET attempts = ?",
                properties.security().authRequestsPerMinute());
        var denied = auth("login", credentials("other", "correct horse battery"));
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.body()).contains("AUTH_RATE_LIMIT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limit", Integer.class))
                .isEqualTo(properties.security().authRequestsPerMinute() + 1);
        jdbc.update(
                "UPDATE auth_rate_limit SET window_started_at = clock_timestamp() - interval '61"
                        + " seconds'");
        assertThat(auth("login", credentials("other", "correct horse battery")).statusCode())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refreshWorksAfterAccessExpiryAndRacingLogoutLeavesNoLiveRefreshSession() throws Exception {
        var initial = createUserAndLogin("alice");
        String subject =
                SignedJWT.parse(initial.path("accessToken").asText())
                        .getJWTClaimsSet()
                        .getSubject();
        String expiredAccess =
                token(
                        subject,
                        "translator-api",
                        Instant.now().minusSeconds(120),
                        "translate:write",
                        key);
        assertThat(get(expiredAccess, "/v1/models").statusCode()).isEqualTo(401);
        var renewed = auth("refresh", refreshBody(initial.path("refreshToken").asText()));
        assertThat(renewed.statusCode()).isEqualTo(200);
        var current = json.readTree(renewed.body());
        assertThat(get(current.path("accessToken").asText(), "/v1/models").statusCode())
                .isEqualTo(200);
        assertLogoutWinsRefreshRace(refreshBody(current.path("refreshToken").asText()));
    }

    private void assertLogoutWinsRefreshRace(String body) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var rotating =
                    workers.submit(
                            () -> {
                                start.await();
                                return auth("refresh", body);
                            });
            var loggingOut =
                    workers.submit(
                            () -> {
                                start.await();
                                return auth("logout", body);
                            });
            start.countDown();
            var refreshed = rotating.get(15, TimeUnit.SECONDS);
            assertThat(refreshed.statusCode()).isIn(200, 401);
            assertThat(loggingOut.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(auth("refresh", body).statusCode()).isEqualTo(401);
            if (refreshed.statusCode() == 200) {
                assertThat(
                                auth(
                                                "refresh",
                                                refreshBody(
                                                        json.readTree(refreshed.body())
                                                                .path("refreshToken")
                                                                .asText()))
                                        .statusCode())
                        .isEqualTo(401);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_refresh_family WHERE revoked_at IS"
                                            + " NULL",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void anonymousFailuresDoNotBlockAnotherAccountLoginRefreshOrLogout() throws Exception {
        var initial = createUserAndLogin("alice");
        assertThat(auth("login", credentials("unknown", "incorrect password")).statusCode())
                .isEqualTo(401);
        jdbc.update(
                "UPDATE auth_rate_limit SET attempts = ? WHERE client_hash = ?",
                properties.security().authRequestsPerMinute(),
                sha256("missing-login:127.0.0.1"));
        assertThat(auth("login", credentials("other_unknown", "incorrect password")).statusCode())
                .isEqualTo(429);
        assertThat(auth("login", credentials("ALICE", "correct horse battery")).statusCode())
                .isEqualTo(200);
        var refreshed = auth("refresh", refreshBody(initial.path("refreshToken").asText()));
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(auth("refresh", refreshBody("a".repeat(43))).statusCode()).isEqualTo(401);
        jdbc.update(
                "UPDATE auth_rate_limit SET attempts = ? WHERE client_hash = ?",
                properties.security().authRequestsPerMinute(),
                sha256("invalid-session:127.0.0.1"));
        // A separate invalid-session bucket can be exhausted without affecting a real family.
        assertThat(auth("refresh", refreshBody("a".repeat(43))).statusCode()).isEqualTo(429);
        var current = json.readTree(refreshed.body());
        var afterFlood = auth("refresh", refreshBody(current.path("refreshToken").asText()));
        assertThat(afterFlood.statusCode()).isEqualTo(200);
        assertThat(
                        auth(
                                        "logout",
                                        refreshBody(
                                                json.readTree(afterFlood.body())
                                                        .path("refreshToken")
                                                        .asText()))
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_refresh_family WHERE revoked_at IS NOT"
                                        + " NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void replayStillRevokesTheFamilyWhenSessionAdmissionIsExhausted() throws Exception {
        var initial = createUserAndLogin("alice");
        var next =
                json.readTree(
                        auth("refresh", refreshBody(initial.path("refreshToken").asText())).body());
        jdbc.update(
                "UPDATE auth_rate_limit SET attempts = ?",
                properties.security().authRequestsPerMinute());
        assertThat(auth("refresh", refreshBody(initial.path("refreshToken").asText())).statusCode())
                .isEqualTo(401);
        assertThat(auth("refresh", refreshBody(next.path("refreshToken").asText())).statusCode())
                .isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_refresh_family WHERE revoked_at IS NOT"
                                        + " NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void globalBudgetCannotBeResetByRegisteringAnotherAccount() throws Exception {
        var first = createUserAndLogin("first");
        var second = createUserAndLogin("second");
        assertThat(post(first.path("accessToken").asText(), request()).statusCode()).isEqualTo(200);
        jdbc.update(
                "UPDATE translation_request SET charged_tokens = ?",
                properties.limits().globalDailyTokens());
        var response = post(second.path("accessToken").asText(), request());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("GLOBAL_DAILY_QUOTA");
        assertThat(providerCalls.get()).isEqualTo(1);
        // Previous days do not consume today's shared budget.
        jdbc.update(
                "UPDATE translation_request SET created_at = clock_timestamp() - interval '1 day'");
        assertThat(post(second.path("accessToken").asText(), request()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void globalConcurrencyIsAtomicAcrossDifferentAccountsAndRecoversExpiredLeases()
            throws Exception {
        var command = new TranslationCommand("Hello", "en", "ru");
        var model = properties.models().get("translation-fast");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String account = "global-account-" + i;
                calls.add(
                        workers.submit(
                                () ->
                                        reserveAfter(
                                                start,
                                                account,
                                                model,
                                                command,
                                                "GLOBAL_CONCURRENCY_LIMIT")));
            }
            start.countDown();
            assertThat(countAdmitted(calls))
                    .isEqualTo(properties.limits().globalConcurrentRequests());
        }
        jdbc.update(
                "UPDATE translation_request SET lease_expires_at = "
                        + "clock_timestamp() - interval '1 second'");
        quota.reserve("new-account", "translation-fast", model, command);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM translation_request "
                                        + "WHERE lease_expires_at > clock_timestamp()",
                                Integer.class))
                .isEqualTo(1);
    }

    private boolean reserveAfter(
            CountDownLatch start,
            String account,
            TranslatorProperties.Model model,
            TranslationCommand command,
            String expectedDenial)
            throws Exception {
        start.await();
        try {
            quota.reserve(account, "translation-fast", model, command);
            return true;
        } catch (ApiException exception) {
            assertThat(exception.code()).isEqualTo(expectedDenial);
            return false;
        }
    }

    private int countAdmitted(List<Future<Boolean>> results) throws Exception {
        int admitted = 0;
        for (var result : results) {
            if (result.get(10, TimeUnit.SECONDS)) {
                admitted++;
            }
        }
        return admitted;
    }

    @Test
    void slowPublicUploadsDoNotHoldWorkersAndExpireAtTheBodyDeadline() throws Exception {
        try (var first =
                        RawHttpTestClient.partial(
                                port, "/v1/auth/login", "Content-Length: 1024\r\n");
                var second =
                        RawHttpTestClient.partial(
                                port, "/v1/auth/login", "Content-Length: 1024\r\n")) {
            // This server has only two workers, both of which the old blocking filter exhausted.
            var response =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + port + "/v1/models"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(RawHttpTestClient.statusLine(first)).contains("408");
            assertThat(RawHttpTestClient.statusLine(second)).contains("408");
        }
        assertThat(auth("login", credentials("missing", "correct horse battery")).statusCode())
                .isEqualTo(401);
    }

    @Test
    void protectedIncompleteBodiesAreRejectedBeforeReadAndDeclaredOversizeIsImmediate()
            throws Exception {
        try (var unauthorized =
                RawHttpTestClient.partial(port, "/v1/translations", "Content-Length: 1024\r\n")) {
            assertThat(RawHttpTestClient.statusLine(unauthorized)).contains("401");
        }
        try (var oversized =
                RawHttpTestClient.partial(port, "/v1/auth/login", "Content-Length: 1000000\r\n")) {
            assertThat(RawHttpTestClient.statusLine(oversized)).contains("413");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_rate_limit", Integer.class))
                .isZero();
    }

    @Test
    void chunkedBodiesStillEnforceTheByteLimit() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000);
            byte[] body =
                    "x"
                            .repeat(properties.limits().maxRequestBytes() + 1)
                            .getBytes(StandardCharsets.US_ASCII);
            var stream = socket.getOutputStream();
            stream.write(
                    ("POST /v1/auth/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: "
                                    + "application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                                    + Integer.toHexString(body.length)
                                    + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            stream.write(body);
            stream.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            stream.flush();
            assertThat(RawHttpTestClient.statusLine(socket)).contains("413");
        }
    }

    @Test
    void uploadCapacityIsBoundedAndPermitsRecoverAfterDisconnect() throws Exception {
        try (var first =
                        RawHttpTestClient.partial(
                                port, "/v1/auth/login", "Content-Length: 1024\r\n");
                var second =
                        RawHttpTestClient.partial(
                                port, "/v1/auth/login", "Content-Length: 1024\r\n")) {
            Thread.sleep(100);
            var rejected = auth("login", credentials("missing", "correct horse battery"));
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).contains("UPLOAD_CAPACITY");
        }
        // The close/error callbacks release slots; retry only while those callbacks finish.
        HttpResponse<String> recovered = null;
        for (int i = 0; i < 40; i++) {
            recovered = auth("login", credentials("missing", "correct horse battery"));
            if (recovered.statusCode() != 503) {
                break;
            }
            Thread.sleep(25);
        }
        assertThat(recovered.statusCode()).isEqualTo(401);
    }

    @Test
    void uploadDeadlineIsAbsoluteEvenWhenBytesKeepArriving() throws Exception {
        try (var socket =
                        RawHttpTestClient.partial(
                                port, "/v1/auth/login", "Content-Length: 1024\r\n");
                var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = reader.submit(() -> RawHttpTestClient.statusLine(socket));
            long start = System.nanoTime();
            for (int i = 0; i < 30 && !response.isDone(); i++) {
                try {
                    socket.getOutputStream().write(' ');
                    socket.getOutputStream().flush();
                } catch (IOException closed) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(response.get(1, TimeUnit.SECONDS)).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void normalAsyncUploadsKeepTheirConnectionReusable() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000);
            String authorization = validToken("keepalive");
            var input = new BufferedInputStream(socket.getInputStream());
            for (int i = 0; i < 2; i++) {
                RawHttpTestClient.sendTranslation(socket, authorization, request());
                RawHttpTestClient.assertSuccessfulResponse(input);
            }
            assertThat(providerCalls.get()).isEqualTo(2);
        }
    }

    private void assertRegisteredIdentity(
            JsonNode tokens, JWTClaimsSet claims, Map<String, Object> user) throws Exception {
        assertThat(claims.getSubject()).isEqualTo(user.get("user_id").toString());
        assertThat(claims.getIssuer()).isEqualTo(issuer);
        assertThat(claims.getAudience()).containsExactly("translator-api");
        assertThat(claims.getStringClaim("scope")).isEqualTo("translate:write");
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(claims.getIssueTime().toInstant().plusSeconds(900));
        assertThat(tokens.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(tokens.path("refreshExpiresIn").asLong()).isEqualTo(30 * 86400);
        assertThat(user.get("username")).isEqualTo("alice");
        assertThat((String) user.get("password_hash"))
                .startsWith("$2a$12$")
                .doesNotContain("correct horse");
        String refresh = tokens.path("refreshToken").asText();
        assertThat(refresh).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_refresh_token", String.class))
                .isEqualTo(sha256(refresh));
    }

    private void assertIssuedTokenPermissions(JsonNode tokens, JWTClaimsSet claims)
            throws Exception {
        assertThat(post(tokens.path("accessToken").asText(), request()).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT account_id FROM translation_request", String.class))
                .isEqualTo(sha256("translator\0" + claims.getSubject()));
        assertThat(get(tokens.path("refreshToken").asText(), "/v1/models").statusCode())
                .isEqualTo(401);
        assertThat(get(tokens.path("accessToken").asText(), "/v1/models").body())
                .doesNotContain("translation-quality");
    }

    private void assertLoginAndCredentialFailures(JWTClaimsSet claims) throws Exception {
        var login = auth("login", credentials("ALICE", "correct horse battery"));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(
                        SignedJWT.parse(json.readTree(login.body()).path("accessToken").asText())
                                .getJWTClaimsSet()
                                .getSubject())
                .isEqualTo(claims.getSubject());
        assertThat(register(credentials("alice", "other correct password")).statusCode())
                .isEqualTo(409);
        var badPassword = auth("login", credentials("alice", "wrong password value"));
        var missingUser = auth("login", credentials("unknown", "wrong password value"));
        assertThat(badPassword.statusCode()).isEqualTo(401);
        assertThat(missingUser.statusCode()).isEqualTo(401);
        assertThat(badPassword.body()).isEqualTo(missingUser.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_family", Integer.class))
                .isEqualTo(2);
    }

    private HttpResponse<String> auth(String action, String body) throws Exception {
        return auth(action, body, null);
    }

    private HttpResponse<String> auth(String action, String body, String accessToken) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/auth/" + action))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String body) throws Exception {
        String adminToken =
                token(
                        "test-admin",
                        "translator-api",
                        Instant.now().plusSeconds(300),
                        "users:create",
                        key);
        return auth("register", body, adminToken);
    }

    private JsonNode createUserAndLogin(String username) throws Exception {
        assertThat(register(credentials(username, userPassword)).statusCode()).isEqualTo(201);
        return login(username, userPassword);
    }

    private JsonNode login(String username, String password) throws Exception {
        var response = auth("login", credentials(username, password));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode refresh(JsonNode session) throws Exception {
        var response = auth("refresh", refreshBody(session.path("refreshToken").asText()));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private String credentials(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String passwordChangeBody(String currentPassword, String newPassword) throws Exception {
        return json.writeValueAsString(
                Map.of("currentPassword", currentPassword, "newPassword", newPassword));
    }

    private String refreshBody(String token) throws Exception {
        return json.writeValueAsString(Map.of("refreshToken", token));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> post(String accessToken, String body) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/translations"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String request() {
        return "{\"text\":\"Good morning!\",\"sourceLanguage\":\"en\","
                + "\"targetLanguage\":\"ru\",\"model\":\"translation-fast\"}";
    }

    private String validToken(String subject) throws Exception {
        return token(
                subject, "translator-api", Instant.now().plusSeconds(300), "translate:write", key);
    }

    private String token(
            String subject, String audience, Instant expires, String scope, RSAKey signingKey)
            throws Exception {
        var builder =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .audience(audience)
                        .issueTime(new Date())
                        .claim("scope", scope);
        if (expires != null) {
            builder.expirationTime(Date.from(expires));
        }
        return sign(builder.build(), signingKey);
    }

    private String sign(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        var signed =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
        signed.sign(new RSASSASigner(signingKey));
        return signed.serialize();
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String privateKeyLocation() {
        try {
            var path = Files.createTempFile("translator-test-signing-", ".pem");
            String encoded =
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(key.toPrivateKey().getEncoded());
            Files.writeString(
                    path,
                    "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
            path.toFile().deleteOnExit();
            return path.toUri().toString();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/v1/responses",
                    exchange -> {
                        providerCalls.incrementAndGet();
                        capturedRequest.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        byte[] response = upstreamResponse.get().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        try (var stream = exchange.getResponseBody()) {
                            stream.write(response);
                        }
                    });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}

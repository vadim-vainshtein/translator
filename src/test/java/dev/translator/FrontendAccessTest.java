package dev.translator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.translator.auth.AuthController;
import dev.translator.auth.AuthRateLimiter;
import dev.translator.auth.AuthService;
import dev.translator.config.SecurityConfig;
import dev.translator.config.TranslatorProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@SpringBootTest(
        classes = {SecurityConfig.class, AuthController.class, FrontendAccessTest.WebApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendAccessTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtEncoder encoder;

    @MockitoBean
    private AuthService auth;

    @MockitoBean
    private AuthRateLimiter limiter;

    @Test
    void browserAssetsArePublicWithoutOpeningOtherPaths() throws Exception {
        var page = get("/", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(page.body()).contains("id=\"source-text\"", "/app.css", "/app.js");
        assertThat(get("/index.html", null).body()).isEqualTo(page.body());

        var stylesheet = get("/app.css", null);
        assertThat(stylesheet.statusCode()).isEqualTo(200);
        assertThat(stylesheet.headers().firstValue("Content-Type").orElse("")).contains("text/css");

        var script = get("/app.js", null);
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).contains("/v1/translations", "/v1/auth/login");

        var sessionStore = get("/session-store.js", null);
        assertThat(sessionStore.statusCode()).isEqualTo(200);
        assertThat(sessionStore.body()).contains("class BrowserSessionStore");
        assertThat(page.body()).contains("/session-store.js");

        assertThat(get("/application.yaml", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(401);
        assertThat(post("/app.js", null).statusCode()).isEqualTo(401);
        assertThat(post("/session-store.js", null).statusCode()).isEqualTo(401);
    }

    @Test
    void translationApiStillRequiresAuthenticationAndScope() throws Exception {
        assertThat(get("/v1/models", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/translation-cost", null).statusCode()).isEqualTo(401);
        assertThat(post("/v1/translations", null).statusCode()).isEqualTo(401);

        String token = tokenWithScope("unrelated:scope");
        assertThat(get("/v1/models", token).statusCode()).isEqualTo(403);
        assertThat(get("/v1/translation-cost", token).statusCode()).isEqualTo(403);
        assertThat(post("/v1/translations", token).statusCode()).isEqualTo(403);
        assertThat(get("/application.yaml", token).statusCode()).isEqualTo(403);
    }

    @Test
    void accountCreationRequiresAdminScopeAndBypassesTheAnonymousRateLimiter() throws Exception {
        String credentials = """
                {"username":"alice","password":"correct horse battery"}
                """;
        String path = "/v1/auth/register";
        assertThat(post(path, null, credentials).statusCode()).isEqualTo(401);
        assertThat(post(path, tokenWithScope("translate:write"), credentials).statusCode())
                .isEqualTo(403);
        verifyNoInteractions(auth);

        var created = post(path, tokenWithScope("translate:write users:create"), credentials);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).isEmpty();
        verify(auth).register("alice", "correct horse battery");
        verifyNoInteractions(limiter);
    }

    @Test
    void passwordChangeRequiresAuthenticationAndPassesTheTokenSubject() throws Exception {
        String passwords = """
                {"currentPassword":"correct horse battery","newPassword":"another long password"}
                """;
        String path = "/v1/auth/change-password";
        assertThat(post(path, null, passwords).statusCode()).isEqualTo(401);
        assertThat(post(path, "invalid-token", passwords).statusCode()).isEqualTo(401);
        verifyNoInteractions(auth);

        var changed = post(path, tokenWithScope("translate:write"), passwords);
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(changed.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store");
        assertThat(changed.body()).isEmpty();
        verify(auth).changePassword("frontend-test", "correct horse battery", "another long password");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return post(path, token, "{}");
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var request =
                request(path, token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private String tokenWithScope(String scope) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer("translator")
                        .audience(List.of("translator-api"))
                        .subject("frontend-test")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("scope", scope)
                        .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class})
    static class WebApplication {
        @Bean
        TranslatorProperties translatorProperties() throws Exception {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var key = generator.generateKeyPair().getPrivate();
            var encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
            var file = Files.createTempFile("translator-frontend-test-", ".pem");
            Files.writeString(
                    file,
                    "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
            file.toFile().deleteOnExit();
            var security =
                    new TranslatorProperties.Security(
                            "translator",
                            "translator-api",
                            file.toUri().toString(),
                            Duration.ofMinutes(15),
                            Duration.ofDays(30),
                            30);
            var openai =
                    new TranslatorProperties.Openai(
                            URI.create("http://localhost:1/"),
                            "unused-test-key",
                            Duration.ofSeconds(1),
                            262144);
            var limits =
                    new TranslatorProperties.Limits(
                            20, 2, 200000, Duration.ofSeconds(60), 65536,
                            8, 2000000, Duration.ofSeconds(5), 16);
            var model =
                    new TranslatorProperties.Model("openai", "unused", 20000, 4096, true, null);
            return new TranslatorProperties(security, openai, limits, Map.of("translation-fast", model));
        }
    }
}

package dev.translator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("translator")
public record TranslatorProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Openai openai,
        @Valid @NotNull Limits limits,
        @NotEmpty Set<@Pattern(regexp = "[a-z]{2,3}(-[A-Za-z0-9]{2,8})*") String> languages,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]{1,100}") String, @Valid Model> models) {

    @AssertTrue(message = "lease must exceed the provider timeout by at least 10 seconds")
    public boolean isLeaseLongEnough() {
        return limits != null && openai != null && limits.lease() != null && openai.timeout() != null
                && limits.lease().compareTo(openai.timeout().plusSeconds(10)) >= 0;
    }

    public record Security(@NotBlank String issuer, @NotBlank String audience,
                           @NotBlank String privateKeyLocation, @NotNull Duration accessTokenTtl,
                           @NotNull Duration refreshTokenTtl, @Min(1) @Max(10000) int authRequestsPerMinute,
                           boolean registrationEnabled) {
        @AssertTrue(message = "access TTL must be 1 minute to 1 hour; refresh TTL must be 1 hour to 90 days and exceed access TTL")
        public boolean isValidTokenLifetime() {
            return accessTokenTtl != null && refreshTokenTtl != null
                    && accessTokenTtl.compareTo(Duration.ofMinutes(1)) >= 0
                    && accessTokenTtl.compareTo(Duration.ofHours(1)) <= 0
                    && refreshTokenTtl.compareTo(Duration.ofHours(1)) >= 0
                    && refreshTokenTtl.compareTo(Duration.ofDays(90)) <= 0
                    && refreshTokenTtl.compareTo(accessTokenTtl) > 0;
        }
    }

    public record Openai(@NotNull URI baseUrl, @NotBlank String apiKey, @NotNull Duration timeout,
                         @Min(1024) @Max(1048576) int maxResponseBytes) {
        @AssertTrue(message = "OpenAI URL must use HTTPS, or HTTP on localhost for testing")
        public boolean isSafeUrl() {
            if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getHost().isBlank()
                    || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) return false;
            return "https".equals(baseUrl.getScheme()) || ("http".equals(baseUrl.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost()));
        }
        @AssertTrue(message = "provider timeout must be between one and 120 seconds")
        public boolean isValidTimeout() {
            return timeout != null && timeout.compareTo(Duration.ofSeconds(1)) >= 0 && timeout.compareTo(Duration.ofSeconds(120)) <= 0;
        }
    }

    public record Limits(@Min(1) int requestsPerMinute, @Min(1) int concurrentRequests,
                         @Min(1) long dailyTokens, @NotNull Duration lease,
                         @Min(1024) @Max(1048576) int maxRequestBytes,
                         @Min(1) int globalConcurrentRequests, @Min(1) long globalDailyTokens,
                         @NotNull Duration bodyTimeout, @Min(1) @Max(1024) int concurrentBodies) {
        @AssertTrue(message = "body timeout must be between 100 milliseconds and 30 seconds")
        public boolean isValidBodyTimeout() {
            return bodyTimeout != null && bodyTimeout.compareTo(Duration.ofMillis(100)) >= 0
                    && bodyTimeout.compareTo(Duration.ofSeconds(30)) <= 0;
        }
    }

    public record Model(@Pattern(regexp = "openai") @NotBlank String provider,
                        @NotBlank @Size(max = 200) String model,
                        @Min(1) @Max(100000) int maxInputBytes,
                        @Min(1) @Max(32768) int maxOutputTokens,
                        @AssertTrue boolean structuredOutput,
                        String requiredScope) {}
}

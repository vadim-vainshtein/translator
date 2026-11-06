package dev.translator.quota;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderResult;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaService {
    private final JdbcTemplate jdbc;
    private final TranslatorProperties.Limits limits;

    public QuotaService(JdbcTemplate jdbc, TranslatorProperties properties) {
        this.jdbc = jdbc;
        this.limits = properties.limits();
    }

    @Transactional
    public UUID reserve(String account, String alias, TranslatorProperties.Model model, TranslationCommand command) {
        // One database lock serializes admission across accounts and application instances.
        jdbc.queryForObject("SELECT singleton FROM quota_global WHERE singleton = 1 FOR UPDATE", Integer.class);
        jdbc.update("INSERT INTO quota_account(account_id) VALUES (?) ON CONFLICT DO NOTHING", account);
        jdbc.queryForObject("SELECT account_id FROM quota_account WHERE account_id = ? FOR UPDATE", String.class, account);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime day = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE translation_request SET status = 'EXPIRED', completed_at = ?, error_code = 'LEASE_EXPIRED' WHERE account_id = ? AND status = 'RUNNING' AND lease_expires_at <= ?",
                now, account, now);
        var counts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE created_at > ?) AS recent,
                       count(*) FILTER (WHERE status = 'RUNNING' AND lease_expires_at > ?) AS running,
                       coalesce(sum(charged_tokens) FILTER (WHERE created_at >= ?), 0) AS tokens
                FROM translation_request WHERE account_id = ? AND (created_at >= ? OR status = 'RUNNING')
                """, now.minusMinutes(1), now, day, account, day.minusMinutes(1));
        if (((Number) counts.get("recent")).longValue() >= limits.requestsPerMinute()) throw exceeded("RATE_LIMIT", "Request rate limit exceeded.");
        if (((Number) counts.get("running")).longValue() >= limits.concurrentRequests()) throw exceeded("CONCURRENCY_LIMIT", "Too many translations are already running.");
        long reserved = TranslationPrompt.tokenReservation(command, model.maxOutputTokens());
        if (((Number) counts.get("tokens")).longValue() > limits.dailyTokens() - reserved) throw exceeded("DAILY_QUOTA", "Daily translation token quota exceeded.");
        var global = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'RUNNING' AND lease_expires_at > ?) AS running,
                       coalesce(sum(charged_tokens) FILTER (WHERE created_at >= ?), 0) AS tokens
                FROM translation_request WHERE created_at >= ? OR status = 'RUNNING'
                """, now, day, day);
        if (((Number) global.get("running")).longValue() >= limits.globalConcurrentRequests())
            throw exceeded("GLOBAL_CONCURRENCY_LIMIT", "The translation service is at capacity.");
        if (((Number) global.get("tokens")).longValue() > limits.globalDailyTokens() - reserved)
            throw exceeded("GLOBAL_DAILY_QUOTA", "The service translation budget is exhausted.");
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO translation_request(request_id, account_id, model_alias, provider, provider_model,
                    prompt_version, source_language, target_language, created_at, lease_expires_at, status, charged_tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """, requestId, account, alias, model.provider(), model.model(), TranslationPrompt.VERSION,
                command.sourceLanguage(), command.targetLanguage(), now, now.plus(limits.lease()), reserved);
        return requestId;
    }

    @Transactional
    public void success(UUID requestId, ProviderResult result) {
        // The original created_at anchors charging to the admission day even across midnight.
        jdbc.update("""
                UPDATE translation_request SET status = 'SUCCEEDED', completed_at = clock_timestamp(),
                    input_tokens = ?, output_tokens = ?,
                    charged_tokens = coalesce(CAST(? AS bigint), charged_tokens)
                WHERE request_id = ? AND status = 'RUNNING'
                """, result.inputTokens(), result.outputTokens(),
                result.inputTokens() != null && result.outputTokens() != null ? result.inputTokens() + result.outputTokens() : null, requestId);
    }

    @Transactional
    public void failure(UUID requestId, String code) {
        // An upstream error/timeout may still have incurred cost: retain the reservation.
        jdbc.update("UPDATE translation_request SET status = 'FAILED', completed_at = clock_timestamp(), error_code = ? WHERE request_id = ? AND status = 'RUNNING'", code, requestId);
    }

    private ApiException exceeded(String code, String message) { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message); }
}

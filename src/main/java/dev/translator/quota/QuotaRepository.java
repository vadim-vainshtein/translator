package dev.translator.quota;

import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderResult;
import dev.translator.translation.TranslationProvider.ProviderUsage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Repository
public class QuotaRepository {
    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockAdmission(String account) {
        // Always lock deployment first, then account, across all application instances.
        jdbc.queryForObject(
                "SELECT singleton FROM quota_global WHERE singleton = 1 FOR UPDATE", Integer.class);
        jdbc.update(
                "INSERT INTO quota_account(account_id) VALUES (?) ON CONFLICT DO NOTHING", account);
        jdbc.queryForObject(
                "SELECT account_id FROM quota_account WHERE account_id = ? FOR UPDATE",
                String.class,
                account);
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    public void expireLeases(String account, OffsetDateTime now) {
        jdbc.update(
                """
                UPDATE translation_request
                SET status = 'EXPIRED', completed_at = ?, error_code = 'LEASE_EXPIRED'
                WHERE account_id = ? AND status = 'RUNNING' AND lease_expires_at <= ?
                """,
                now,
                account,
                now);
    }

    public Usage accountUsage(String account, OffsetDateTime now, OffsetDateTime day) {
        var counts =
                jdbc.queryForMap(
                        """
                        SELECT count(*) FILTER (WHERE created_at > ?) AS recent,
                               count(*) FILTER (WHERE status = 'RUNNING' AND lease_expires_at > ?) AS running,
                               coalesce(sum(charged_tokens) FILTER (WHERE created_at >= ?), 0) AS tokens
                        FROM translation_request
                        WHERE account_id = ? AND (created_at >= ? OR status = 'RUNNING')
                        """,
                        now.minusMinutes(1),
                        now,
                        day,
                        account,
                        day.minusMinutes(1));
        return new Usage(
                count(counts, "recent"), count(counts, "running"), count(counts, "tokens"));
    }

    public Usage globalUsage(OffsetDateTime now, OffsetDateTime day) {
        var counts =
                jdbc.queryForMap(
                        """
                        SELECT count(*) FILTER (WHERE status = 'RUNNING' AND lease_expires_at > ?) AS running,
                               coalesce(sum(charged_tokens) FILTER (WHERE created_at >= ?), 0) AS tokens
                        FROM translation_request WHERE created_at >= ? OR status = 'RUNNING'
                        """,
                        now,
                        day,
                        day);
        return new Usage(0, count(counts, "running"), count(counts, "tokens"));
    }

    public CostSummary costSummary(String account) {
        return jdbc.queryForObject(
                """
                SELECT coalesce(sum(cost_usd), 0) AS total_cost_usd,
                       count(*) FILTER (WHERE cost_usd IS NULL) AS unpriced_requests
                FROM translation_request
                WHERE account_id = ? AND status <> 'RUNNING'
                """,
                (row, rowNumber) -> new CostSummary(
                        row.getBigDecimal("total_cost_usd"), row.getLong("unpriced_requests")),
                account);
    }

    public void reserve(
            UUID requestId,
            String account,
            String alias,
            TranslatorProperties.Model model,
            TranslationCommand command,
            OffsetDateTime now,
            OffsetDateTime leaseExpires,
            long reserved) {
        jdbc.update(
                """
                INSERT INTO translation_request(
                    request_id, account_id, model_alias, provider, provider_model, prompt_version,
                    source_language, target_language, created_at, lease_expires_at, status,
                    charged_tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """,
                requestId,
                account,
                alias,
                model.provider(),
                model.model(),
                TranslationPrompt.version(command),
                command.sourceLanguage(),
                command.targetLanguage(),
                now,
                leaseExpires,
                reserved);
    }

    public void success(UUID requestId, ProviderResult result, BigDecimal costUsd) {
        // Admission time anchors charging to its original day, even across midnight.
        // Record late provider usage without reopening an expired quota lease.
        ProviderUsage usage = result.usage();
        jdbc.update(
                """
                UPDATE translation_request
                SET status = CASE WHEN status = 'RUNNING' THEN 'SUCCEEDED' ELSE status END,
                    completed_at = CASE WHEN status = 'RUNNING' THEN clock_timestamp() ELSE completed_at END,
                    charged_tokens = CASE WHEN status = 'RUNNING'
                        THEN coalesce(CAST(? AS bigint), charged_tokens) ELSE charged_tokens END,
                    input_tokens = ?, output_tokens = ?, cached_input_tokens = ?, cost_usd = ?,
                    source_language = coalesce(?, source_language)
                WHERE request_id = ? AND status IN ('RUNNING', 'EXPIRED')
                """,
                chargedTokens(usage),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                costUsd,
                result.detectedSourceLanguage(),
                requestId);
    }

    public void failure(UUID requestId, String code, ProviderUsage usage, BigDecimal costUsd) {
        // Keep the reserved quota charge and record any billed provider usage.
        jdbc.update(
                """
                UPDATE translation_request
                SET status = CASE WHEN status = 'RUNNING' THEN 'FAILED' ELSE status END,
                    completed_at = CASE WHEN status = 'RUNNING' THEN clock_timestamp() ELSE completed_at END,
                    error_code = CASE WHEN status = 'RUNNING' THEN ? ELSE error_code END,
                    input_tokens = coalesce(input_tokens, ?),
                    output_tokens = coalesce(output_tokens, ?),
                    cached_input_tokens = coalesce(cached_input_tokens, ?),
                    cost_usd = coalesce(cost_usd, ?)
                WHERE request_id = ? AND status IN ('RUNNING', 'EXPIRED')
                """,
                code,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                costUsd,
                requestId);
    }

    private Long chargedTokens(ProviderUsage usage) {
        if (usage.inputTokens() == null || usage.outputTokens() == null) {
            return null;
        }
        return usage.inputTokens() + usage.outputTokens();
    }

    private long count(Map<String, Object> counts, String key) {
        return ((Number) counts.get(key)).longValue();
    }

    public record Usage(long recent, long running, long tokens) {}
}

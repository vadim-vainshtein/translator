package dev.translator.quota;

import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
                TranslationPrompt.VERSION,
                command.sourceLanguage(),
                command.targetLanguage(),
                now,
                leaseExpires,
                reserved);
    }

    public void success(UUID requestId, ProviderResult result) {
        // Admission time anchors charging to its original day, even across midnight.
        jdbc.update(
                """
                UPDATE translation_request SET status = 'SUCCEEDED', completed_at = clock_timestamp(),
                    input_tokens = ?, output_tokens = ?,
                    charged_tokens = coalesce(CAST(? AS bigint), charged_tokens)
                WHERE request_id = ? AND status = 'RUNNING'
                """,
                result.inputTokens(),
                result.outputTokens(),
                chargedTokens(result),
                requestId);
    }

    public void failure(UUID requestId, String code) {
        // Errors/timeouts may incur upstream cost: retain the reservation.
        jdbc.update(
                """
                UPDATE translation_request
                SET status = 'FAILED', completed_at = clock_timestamp(), error_code = ?
                WHERE request_id = ? AND status = 'RUNNING'
                """,
                code,
                requestId);
    }

    private Long chargedTokens(ProviderResult result) {
        if (result.inputTokens() == null || result.outputTokens() == null) {
            return null;
        }
        return result.inputTokens() + result.outputTokens();
    }

    private long count(Map<String, Object> counts, String key) {
        return ((Number) counts.get(key)).longValue();
    }

    public record Usage(long recent, long running, long tokens) {}
}

package dev.translator.quota;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderResult;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class QuotaService {
    private final QuotaRepository repository;
    private final TranslatorProperties.Limits limits;

    public QuotaService(QuotaRepository repository, TranslatorProperties properties) {
        this.repository = repository;
        this.limits = properties.limits();
    }

    @Transactional
    public UUID reserve(
            String account,
            String alias,
            TranslatorProperties.Model model,
            TranslationCommand command) {
        repository.lockAdmission(account);
        OffsetDateTime now = repository.now();
        OffsetDateTime day = startOfUtcDay(now);
        repository.expireLeases(account, now);
        var accountUsage = repository.accountUsage(account, now, day);
        requireAccountCapacity(accountUsage);
        long reserved = TranslationPrompt.tokenReservation(command, model.maxOutputTokens());
        requireAccountBudget(accountUsage, reserved);
        requireGlobalCapacity(repository.globalUsage(now, day), reserved);
        UUID requestId = UUID.randomUUID();
        repository.reserve(
                requestId, account, alias, model, command, now, now.plus(limits.lease()), reserved);
        return requestId;
    }

    @Transactional
    public void success(UUID requestId, ProviderResult result) {
        repository.success(requestId, result);
    }

    @Transactional
    public void failure(UUID requestId, String code) {
        repository.failure(requestId, code);
    }

    private void requireAccountCapacity(QuotaRepository.Usage usage) {
        if (usage.recent() >= limits.requestsPerMinute()) {
            throw exceeded("RATE_LIMIT", "Request rate limit exceeded.");
        }
        if (usage.running() >= limits.concurrentRequests()) {
            throw exceeded("CONCURRENCY_LIMIT", "Too many translations are already running.");
        }
    }

    private void requireAccountBudget(QuotaRepository.Usage usage, long reserved) {
        if (usage.tokens() > limits.dailyTokens() - reserved) {
            throw exceeded("DAILY_QUOTA", "Daily translation token quota exceeded.");
        }
    }

    private void requireGlobalCapacity(QuotaRepository.Usage usage, long reserved) {
        if (usage.running() >= limits.globalConcurrentRequests()) {
            throw exceeded("GLOBAL_CONCURRENCY_LIMIT", "The translation service is at capacity.");
        }
        if (usage.tokens() > limits.globalDailyTokens() - reserved) {
            throw exceeded("GLOBAL_DAILY_QUOTA", "The service translation budget is exhausted.");
        }
    }

    private OffsetDateTime startOfUtcDay(OffsetDateTime now) {
        return now.withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);
    }

    private ApiException exceeded(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}

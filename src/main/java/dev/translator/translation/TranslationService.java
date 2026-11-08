package dev.translator.translation;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.quota.CostSummary;
import dev.translator.quota.QuotaService;
import dev.translator.translation.TranslationProvider.ProviderFailure;
import dev.translator.translation.TranslationProvider.ProviderUsage;
import dev.translator.translation.TranslationProvider.UsageExample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private final ModelRegistry registry;
    private final QuotaService quota;
    private final TranslationPricing pricing;

    public TranslationService(ModelRegistry registry, QuotaService quota, TranslationPricing pricing) {
        this.registry = registry;
        this.quota = quota;
        this.pricing = pricing;
    }

    public CostSummary costSummary(JwtAuthenticationToken authentication) {
        return quota.costSummary(accountId(authentication));
    }

    public TranslationResult translate(
            TranslationCommand command, String alias, JwtAuthenticationToken authentication) {
        var model = registry.select(alias, authentication);
        validate(command, model);
        String account = accountId(authentication);
        UUID requestId = quota.reserve(account, alias, model, command);
        long started = System.nanoTime();
        try {
            var result = registry.provider(model).translate(command, model);
            BigDecimal costUsd = pricing.costUsd(model.model(), result.usage());
            quota.success(requestId, result, costUsd);
            return completed(requestId, alias, command, result, costUsd, started);
        } catch (RuntimeException exception) {
            String code = exception instanceof ApiException api ? api.code() : "INTERNAL_ERROR";
            ProviderUsage usage = exception instanceof ProviderFailure failure
                    ? failure.usage()
                    : ProviderUsage.UNKNOWN;
            quota.failure(requestId, code, usage, pricing.costUsd(model.model(), usage));
            log.info(
                    "Translation request={} model={} outcome={} durationMs={}",
                    requestId,
                    alias,
                    code,
                    (System.nanoTime() - started) / 1_000_000);
            throw exception;
        }
    }

    private void validate(TranslationCommand command, TranslatorProperties.Model model) {
        if ((!command.detectSourceLanguage() && !TranslationLanguage.isValid(command.sourceLanguage()))
                || !TranslationLanguage.isValid(command.targetLanguage())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_LANGUAGE",
                    "Use a language name or code for source and target languages.");
        }
        if (command.text().getBytes(StandardCharsets.UTF_8).length > model.maxInputBytes()) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "TEXT_TOO_LARGE",
                    "Source text exceeds this model's input limit.");
        }
    }

    private TranslationResult completed(
            UUID requestId,
            String alias,
            TranslationCommand command,
            TranslationProvider.ProviderResult result,
            BigDecimal costUsd,
            long started) {
        log.info(
                "Translation request={} model={} outcome=SUCCEEDED durationMs={} inputTokens={}"
                        + " outputTokens={}",
                requestId,
                alias,
                (System.nanoTime() - started) / 1_000_000,
                result.usage().inputTokens(),
                result.usage().outputTokens());
        return new TranslationResult(
                requestId,
                result.translatedText(),
                command.detectSourceLanguage() ? result.detectedSourceLanguage() : command.sourceLanguage(),
                command.targetLanguage(),
                alias,
                costUsd,
                result.alternatives(),
                result.examples());
    }

    private String accountId(JwtAuthenticationToken authentication) {
        try {
            var jwt = authentication.getToken();
            byte[] value =
                    (jwt.getClaimAsString("iss") + "\0" + jwt.getSubject())
                            .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record TranslationResult(
            UUID requestId,
            String translatedText,
            String sourceLanguage,
            String targetLanguage,
            String model,
            BigDecimal costUsd,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<String> alternatives,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<UsageExample> examples) {}
}

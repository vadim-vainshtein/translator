package dev.translator.translation;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.quota.QuotaService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private final ModelRegistry registry;
    private final QuotaService quota;
    private final Set<String> languages;

    public TranslationService(ModelRegistry registry, QuotaService quota, TranslatorProperties properties) {
        this.registry = registry;
        this.quota = quota;
        this.languages = Set.copyOf(properties.languages());
    }

    public TranslationResult translate(TranslationCommand command, String alias, JwtAuthenticationToken authentication) {
        var model = registry.select(alias, authentication);
        if (!languages.contains(command.sourceLanguage()) || !languages.contains(command.targetLanguage())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_LANGUAGE", "Select a supported source and target language.");
        }
        if (command.text().getBytes(StandardCharsets.UTF_8).length > model.maxInputBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TEXT_TOO_LARGE", "Source text exceeds this model's input limit.");
        }
        String account = accountId(authentication);
        UUID requestId = quota.reserve(account, alias, model, command);
        long started = System.nanoTime();
        try {
            var result = registry.provider(model).translate(command, model);
            quota.success(requestId, result);
            log.info("Translation request={} model={} outcome=SUCCEEDED durationMs={} inputTokens={} outputTokens={}",
                    requestId, alias, (System.nanoTime() - started) / 1_000_000, result.inputTokens(), result.outputTokens());
            return new TranslationResult(requestId, result.translatedText(), command.sourceLanguage(), command.targetLanguage(), alias);
        } catch (RuntimeException exception) {
            String code = exception instanceof ApiException api ? api.code() : "INTERNAL_ERROR";
            quota.failure(requestId, code);
            log.info("Translation request={} model={} outcome={} durationMs={}", requestId, alias, code, (System.nanoTime() - started) / 1_000_000);
            throw exception;
        }
    }

    private String accountId(JwtAuthenticationToken authentication) {
        try {
            var jwt = authentication.getToken();
            byte[] value = (jwt.getIssuer() + "\0" + jwt.getSubject()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record TranslationResult(UUID requestId, String translatedText, String sourceLanguage, String targetLanguage, String model) {}
}

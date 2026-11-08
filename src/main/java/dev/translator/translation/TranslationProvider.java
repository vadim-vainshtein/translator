package dev.translator.translation;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;

import java.util.List;

public interface TranslationProvider {
    String id();

    ProviderResult translate(TranslationCommand command, TranslatorProperties.Model model);

    record ProviderResult(
            String translatedText,
            ProviderUsage usage,
            String detectedSourceLanguage,
            List<String> alternatives,
            List<UsageExample> examples) {
        public ProviderResult(String translatedText, Long inputTokens, Long outputTokens) {
            this(
                    translatedText,
                    new ProviderUsage(inputTokens, outputTokens, null),
                    null,
                    List.of(),
                    List.of());
        }
    }

    record UsageExample(String sourceText, String translatedText) {}

    record ProviderUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens) {
        public static final ProviderUsage UNKNOWN = new ProviderUsage(null, null, null);
    }

    final class ProviderFailure extends ApiException {
        private final ProviderUsage usage;

        public ProviderFailure(ApiException failure, ProviderUsage usage) {
            super(failure.status(), failure.code(), failure.getMessage());
            this.usage = usage;
        }

        public ProviderUsage usage() {
            return usage;
        }
    }
}

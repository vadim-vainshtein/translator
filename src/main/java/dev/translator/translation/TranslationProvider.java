package dev.translator.translation;

import dev.translator.config.TranslatorProperties;

public interface TranslationProvider {
    String id();

    ProviderResult translate(TranslationCommand command, TranslatorProperties.Model model);

    record ProviderResult(String translatedText, Long inputTokens, Long outputTokens) {}
}

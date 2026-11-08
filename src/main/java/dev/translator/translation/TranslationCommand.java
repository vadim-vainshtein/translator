package dev.translator.translation;

public record TranslationCommand(
        String text,
        String sourceLanguage,
        String targetLanguage,
        boolean detectSourceLanguage,
        boolean includeUsage) {
    public TranslationCommand(String text, String sourceLanguage, String targetLanguage) {
        this(text, sourceLanguage, targetLanguage, false);
    }

    public TranslationCommand(
            String text, String sourceLanguage, String targetLanguage, boolean detectSourceLanguage) {
        this(text, sourceLanguage, targetLanguage, detectSourceLanguage, false);
    }
}

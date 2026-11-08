package dev.translator.translation;

import java.util.regex.Pattern;

public final class TranslationLanguage {
    public static final int MAX_LENGTH = 80;
    private static final Pattern NAME = Pattern.compile("[\\p{L}][\\p{L}\\p{M}\\p{N} .'’()/-]*");

    private TranslationLanguage() {}

    public static boolean isValid(String value) {
        return value != null && value.length() <= MAX_LENGTH && NAME.matcher(value).matches();
    }
}

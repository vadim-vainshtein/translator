package dev.translator.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderResult;

import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class OpenAiPayload {
    private final ObjectMapper json =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    byte[] encode(TranslationCommand command, TranslatorProperties.Model model) throws IOException {
        var body =
                Map.ofEntries(
                        Map.entry("model", model.model()),
                        Map.entry("store", false),
                        Map.entry("instructions", TranslationPrompt.instructions(command)),
                        Map.entry("input", List.of(inputMessage(command))),
                        Map.entry("max_output_tokens", model.maxOutputTokens()),
                        Map.entry("text", Map.of("format", responseFormat())));
        return json.writeValueAsBytes(body);
    }

    private Map<String, String> inputMessage(TranslationCommand command) throws IOException {
        String content = json.writeValueAsString(Map.of("text", command.text()));
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> responseFormat() {
        return Map.ofEntries(
                Map.entry("type", "json_schema"),
                Map.entry("name", "translation"),
                Map.entry("strict", true),
                Map.entry("schema", translationSchema()));
    }

    private Map<String, Object> translationSchema() {
        return Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.of("translatedText", Map.of("type", "string"))),
                Map.entry("required", List.of("translatedText")),
                Map.entry("additionalProperties", false));
    }

    ProviderResult parse(byte[] bytes) throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null || !"completed".equals(root.path("status").asText())) {
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "INCOMPLETE_TRANSLATION",
                    "The translation provider did not finish the translation.");
        }
        String text = outputText(root);
        String translatedText = translatedText(text);
        Long input = usage(root.path("usage").path("input_tokens"));
        Long output = usage(root.path("usage").path("output_tokens"));
        return new ProviderResult(translatedText, input, output);
    }

    private String translatedText(String text) throws IOException {
        JsonNode result = json.readTree(text);
        if (result == null
                || !result.isObject()
                || result.size() != 1
                || !result.path("translatedText").isTextual()
                || result.path("translatedText").textValue().isBlank()) {
            throw invalid();
        }
        return result.path("translatedText").textValue();
    }

    private String outputText(JsonNode root) {
        String text = null;
        for (var item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            if (!"completed".equals(item.path("status").asText())) {
                throw failure(
                        HttpStatus.BAD_GATEWAY,
                        "INCOMPLETE_TRANSLATION",
                        "The translation provider did not finish the translation.");
            }
            text = messageText(item, text);
        }
        if (text == null) {
            throw invalid();
        }
        return text;
    }

    private String messageText(JsonNode item, String text) {
        for (var content : item.path("content")) {
            if ("refusal".equals(content.path("type").asText())) {
                throw failure(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TRANSLATION_REFUSED",
                        "The provider declined to translate this text.");
            }
            if ("output_text".equals(content.path("type").asText())) {
                if (text != null || !content.path("text").isTextual()) {
                    throw invalid();
                }
                text = content.path("text").textValue();
            }
        }
        return text;
    }

    private Long usage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()
                || !node.canConvertToLong()
                || node.longValue() < 0
                || node.longValue() > 1_000_000_000L) {
            throw invalid();
        }
        return node.longValue();
    }

    private ApiException invalid() {
        return failure(
                HttpStatus.BAD_GATEWAY,
                "INVALID_PROVIDER_RESPONSE",
                "The translation provider returned an invalid response.");
    }

    private ApiException failure(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}

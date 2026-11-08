package dev.translator.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationLanguage;
import dev.translator.translation.TranslationPrompt;
import dev.translator.translation.TranslationProvider.ProviderFailure;
import dev.translator.translation.TranslationProvider.ProviderResult;
import dev.translator.translation.TranslationProvider.ProviderUsage;
import dev.translator.translation.TranslationProvider.UsageExample;

import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class OpenAiPayload {
    private static final int MAX_USAGE_EXAMPLES = 2;

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
                        Map.entry("text", Map.of("format", responseFormat(command))));
        return json.writeValueAsBytes(body);
    }

    private Map<String, String> inputMessage(TranslationCommand command) throws IOException {
        var fields = command.detectSourceLanguage()
                ? Map.of("text", command.text(), "targetLanguage", command.targetLanguage())
                : Map.of(
                        "text", command.text(),
                        "sourceLanguage", command.sourceLanguage(),
                        "targetLanguage", command.targetLanguage());
        String content = json.writeValueAsString(fields);
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> responseFormat(TranslationCommand command) {
        return Map.ofEntries(
                Map.entry("type", "json_schema"),
                Map.entry("name", "translation"),
                Map.entry("strict", true),
                Map.entry("schema", translationSchema(command)));
    }

    private Map<String, Object> translationSchema(TranslationCommand command) {
        var stringType = Map.of("type", "string");
        var fields = new LinkedHashMap<String, Map<String, ?>>();
        fields.put("translatedText", stringType);
        if (command.detectSourceLanguage()) {
            fields.put("detectedSourceLanguage", stringType);
        }
        if (command.includeUsage()) {
            var example = objectSchema(Map.of("sourceText", stringType, "translatedText", stringType));
            fields.put("alternatives", Map.of("type", "array", "items", stringType));
            fields.put("examples", Map.of("type", "array", "items", example));
        }
        return objectSchema(fields);
    }

    private Map<String, Object> objectSchema(Map<String, ?> fields) {
        return Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", fields),
                Map.entry("required", List.copyOf(fields.keySet())),
                Map.entry("additionalProperties", false));
    }

    ProviderResult parse(byte[] bytes, TranslationCommand command) throws IOException {
        JsonNode root = json.readTree(bytes);
        try {
            requireCompleted(root);
            return readTranslation(root, command);
        } catch (ApiException exception) {
            throw withUsage(exception, root);
        } catch (IOException exception) {
            throw withUsage(invalid(), root);
        }
    }

    private ProviderResult readTranslation(JsonNode root, TranslationCommand command) throws IOException {
        JsonNode result = json.readTree(outputText(root));
        int fieldCount = 1 + (command.detectSourceLanguage() ? 1 : 0) + (command.includeUsage() ? 2 : 0);
        if (result == null || !result.isObject() || result.size() != fieldCount) {
            throw invalid();
        }
        String translatedText = requiredText(result.path("translatedText"));
        String detected = command.detectSourceLanguage()
                ? detectedSourceLanguage(result.path("detectedSourceLanguage"))
                : null;
        List<String> alternatives = command.includeUsage()
                ? alternatives(result.path("alternatives"), translatedText)
                : List.of();
        List<UsageExample> examples = command.includeUsage() ? examples(result.path("examples")) : List.of();
        return new ProviderResult(translatedText, readUsage(root), detected, alternatives, examples);
    }

    private void requireCompleted(JsonNode root) {
        if (root == null || !"completed".equals(root.path("status").asText())) {
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "INCOMPLETE_TRANSLATION",
                    "The translation provider did not finish the translation.");
        }
    }

    private ApiException withUsage(ApiException failure, JsonNode root) {
        if (root == null) {
            return failure;
        }
        ProviderUsage usage;
        try {
            usage = readUsage(root);
        } catch (ApiException invalidUsage) {
            return failure;
        }
        return usage.equals(ProviderUsage.UNKNOWN) ? failure : new ProviderFailure(failure, usage);
    }

    private ProviderUsage readUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        Long input = tokens(usage.path("input_tokens"));
        Long output = tokens(usage.path("output_tokens"));
        Long cached = tokens(usage.path("input_tokens_details").path("cached_tokens"));
        if (cached != null && input != null && cached > input) {
            throw invalid();
        }
        return new ProviderUsage(input, output, cached);
    }

    private List<String> alternatives(JsonNode values, String translatedText) {
        if (!values.isArray()) {
            throw invalid();
        }
        var alternatives = new LinkedHashSet<String>();
        for (JsonNode value : values) {
            alternatives.add(requiredText(value));
        }
        alternatives.remove(translatedText);
        return List.copyOf(alternatives);
    }

    private List<UsageExample> examples(JsonNode values) {
        if (!values.isArray()) {
            throw invalid();
        }
        var examples = new ArrayList<UsageExample>();
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() != 2) {
                throw invalid();
            }
            String sourceText = requiredText(value.path("sourceText"));
            String translatedText = requiredText(value.path("translatedText"));
            if (examples.size() < MAX_USAGE_EXAMPLES) {
                examples.add(new UsageExample(sourceText, translatedText));
            }
        }
        return List.copyOf(examples);
    }

    private String requiredText(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw invalid();
        }
        return node.textValue();
    }

    private String detectedSourceLanguage(JsonNode node) {
        if (!node.isTextual()) {
            throw invalid();
        }
        String language = node.textValue().strip();
        if (!TranslationLanguage.isValid(language)) {
            throw invalid();
        }
        return language;
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

    private Long tokens(JsonNode node) {
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

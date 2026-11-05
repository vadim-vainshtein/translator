package dev.translator.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OpenAiProvider implements TranslationProvider {
    private final TranslatorProperties.Openai config;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final HttpClient client;
    private final URI endpoint;

    public OpenAiProvider(TranslatorProperties properties) {
        this.config = properties.openai();
        client = HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var base = config.baseUrl().toString();
        endpoint = URI.create(base.endsWith("/") ? base + "responses" : base + "/responses");
    }

    @Override public String id() { return "openai"; }

    @Override
    public ProviderResult translate(TranslationCommand command, TranslatorProperties.Model model) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var schema = Map.of("type", "object", "properties", Map.of("translatedText", Map.of("type", "string")),
                    "required", List.of("translatedText"), "additionalProperties", false);
            var body = Map.of("model", model.model(), "store", false,
                    "instructions", TranslationPrompt.instructions(command),
                    "input", List.of(Map.of("role", "user", "content", json.writeValueAsString(Map.of("text", command.text())))),
                    "max_output_tokens", model.maxOutputTokens(),
                    "text", Map.of("format", Map.of("type", "json_schema", "name", "translation", "strict", true, "schema", schema)));
            var request = HttpRequest.newBuilder(endpoint).timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            // Completion includes consuming the body, so the deadline also bounds a server
            // that sends headers promptly but stalls while streaming its response.
            pending = client.sendAsync(request, info -> new LimitedBodySubscriber(config.maxResponseBytes()));
            var response = pending.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 429) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_BUSY", "The translation provider is busy.");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "The translation provider rejected the request.");
            }
            return parse(response.body());
        } catch (TimeoutException exception) {
            if (pending != null) pending.cancel(true);
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", "The translation provider timed out.");
        } catch (InterruptedException exception) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "REQUEST_INTERRUPTED", "The translation request was interrupted.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", "The translation provider timed out.");
            }
            throw failure(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "The translation provider could not complete the request.");
        } catch (IOException exception) {
            throw failure(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_RESPONSE", "The translation provider returned an invalid response.");
        }
    }

    private ProviderResult parse(byte[] bytes) throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null || !"completed".equals(root.path("status").asText())) {
            throw failure(HttpStatus.BAD_GATEWAY, "INCOMPLETE_TRANSLATION", "The translation provider did not finish the translation.");
        }
        String text = null;
        for (var item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            if (!"completed".equals(item.path("status").asText())) {
                throw failure(HttpStatus.BAD_GATEWAY, "INCOMPLETE_TRANSLATION", "The translation provider did not finish the translation.");
            }
            for (var content : item.path("content")) {
                if ("refusal".equals(content.path("type").asText())) {
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSLATION_REFUSED", "The provider declined to translate this text.");
                }
                if ("output_text".equals(content.path("type").asText())) {
                    if (text != null || !content.path("text").isTextual()) throw invalid();
                    text = content.path("text").textValue();
                }
            }
        }
        if (text == null) throw invalid();
        JsonNode result = json.readTree(text);
        if (result == null || !result.isObject() || result.size() != 1 || !result.path("translatedText").isTextual()
                || result.path("translatedText").textValue().isBlank()) throw invalid();
        Long input = usage(root.path("usage").path("input_tokens"));
        Long output = usage(root.path("usage").path("output_tokens"));
        return new ProviderResult(result.path("translatedText").textValue(), input, output);
    }

    private Long usage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0 || node.longValue() > 1_000_000_000L) throw invalid();
        return node.longValue();
    }

    private ApiException invalid() { return failure(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_RESPONSE", "The translation provider returned an invalid response."); }
    private ApiException failure(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<java.nio.ByteBuffer> items) {
            for (var item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Response size exceeded"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

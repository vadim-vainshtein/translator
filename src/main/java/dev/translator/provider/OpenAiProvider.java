package dev.translator.provider;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationProvider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OpenAiProvider implements TranslationProvider {
    private final TranslatorProperties.Openai config;
    private final OpenAiPayload payload = new OpenAiPayload();
    private final HttpClient client;
    private final URI endpoint;

    public OpenAiProvider(TranslatorProperties properties) {
        this.config = properties.openai();
        client =
                HttpClient.newBuilder()
                        .connectTimeout(config.timeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        String base = config.baseUrl().toString();
        endpoint = URI.create(base.endsWith("/") ? base + "responses" : base + "/responses");
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public ProviderResult translate(TranslationCommand command, TranslatorProperties.Model model) {
        try {
            var response = send(request(command, model));
            requireSuccessfulResponse(response.statusCode());
            return payload.parse(response.body(), command);
        } catch (IOException exception) {
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_PROVIDER_RESPONSE",
                    "The translation provider returned an invalid response.");
        }
    }

    private HttpRequest request(TranslationCommand command, TranslatorProperties.Model model)
            throws IOException {
        return HttpRequest.newBuilder(endpoint)
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.encode(command, model)))
                .build();
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        // Completion consumes the body, so the deadline also bounds stalled response streams.
        var pending =
                client.sendAsync(
                        request, info -> new LimitedBodySubscriber(config.maxResponseBytes()));
        try {
            return pending.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "REQUEST_INTERRUPTED",
                    "The translation request was interrupted.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw timeout();
            }
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "PROVIDER_ERROR",
                    "The translation provider could not complete the request.");
        }
    }

    private void requireSuccessfulResponse(int status) {
        if (status == 429) {
            throw failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_BUSY",
                    "The translation provider is busy.");
        }
        if (status < 200 || status >= 300) {
            throw failure(
                    HttpStatus.BAD_GATEWAY,
                    "PROVIDER_ERROR",
                    "The translation provider rejected the request.");
        }
    }

    private ApiException timeout() {
        return failure(
                HttpStatus.GATEWAY_TIMEOUT,
                "PROVIDER_TIMEOUT",
                "The translation provider timed out.");
    }

    private ApiException failure(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}

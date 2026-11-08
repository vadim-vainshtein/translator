package dev.translator.api;

import dev.translator.quota.CostSummary;
import dev.translator.translation.ModelRegistry;
import dev.translator.translation.TranslationCommand;
import dev.translator.translation.TranslationLanguage;
import dev.translator.translation.TranslationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class TranslationController {
    private final TranslationService service;
    private final ModelRegistry models;

    public TranslationController(TranslationService service, ModelRegistry models) {
        this.service = service;
        this.models = models;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ModelRegistry.ModelView> models(JwtAuthenticationToken authentication) {
        return models.available(authentication);
    }

    @GetMapping(value = "/translation-cost", produces = MediaType.APPLICATION_JSON_VALUE)
    public CostSummary translationCost(JwtAuthenticationToken authentication) {
        return service.costSummary(authentication);
    }

    @PostMapping(
            value = "/translations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TranslationService.TranslationResult translate(
            @Valid @RequestBody TranslationRequest request, JwtAuthenticationToken authentication) {
        boolean detectSourceLanguage = Boolean.TRUE.equals(request.detectSourceLanguage());
        return service.translate(
                new TranslationCommand(
                        request.text(),
                        detectSourceLanguage ? null : request.sourceLanguage().strip(),
                        request.targetLanguage().strip(),
                        detectSourceLanguage,
                        Boolean.TRUE.equals(request.includeUsage())),
                request.model(),
                authentication);
    }

    public record TranslationRequest(
            @NotBlank @Size(max = 10000) String text,
            @Size(max = TranslationLanguage.MAX_LENGTH) String sourceLanguage,
            @NotBlank @Size(max = TranslationLanguage.MAX_LENGTH) String targetLanguage,
            @NotBlank @Size(max = 100) String model,
            Boolean detectSourceLanguage,
            Boolean includeUsage) {
        @AssertTrue
        public boolean isSourceLanguagePresent() {
            return Boolean.TRUE.equals(detectSourceLanguage)
                    || (sourceLanguage != null && !sourceLanguage.isBlank());
        }
    }
}

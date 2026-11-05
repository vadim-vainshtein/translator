package dev.translator.api;

import dev.translator.translation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class TranslationController {
    private final TranslationService service;
    private final ModelRegistry models;

    public TranslationController(TranslationService service, ModelRegistry models) { this.service = service; this.models = models; }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ModelRegistry.ModelView> models(JwtAuthenticationToken authentication) { return models.available(authentication); }

    @PostMapping(value = "/translations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TranslationService.TranslationResult translate(@Valid @RequestBody TranslationRequest request, JwtAuthenticationToken authentication) {
        return service.translate(new TranslationCommand(request.text(), request.sourceLanguage(), request.targetLanguage()), request.model(), authentication);
    }

    public record TranslationRequest(@NotBlank @Size(max = 10000) String text,
            @NotBlank @Size(max = 20) String sourceLanguage,
            @NotBlank @Size(max = 20) String targetLanguage,
            @NotBlank @Size(max = 100) String model) {}
}

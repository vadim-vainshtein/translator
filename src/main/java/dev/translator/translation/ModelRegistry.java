package dev.translator.translation;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ModelRegistry {
    private final Map<String, TranslatorProperties.Model> models;
    private final Map<String, TranslationProvider> providers;

    public ModelRegistry(TranslatorProperties properties, List<TranslationProvider> providers) {
        this.models = Map.copyOf(properties.models());
        this.providers = indexProviders(providers);
        validateModelProviders();
    }

    private Map<String, TranslationProvider> indexProviders(List<TranslationProvider> providers) {
        var byId = new HashMap<String, TranslationProvider>();
        for (var provider : providers) {
            if (byId.put(provider.id(), provider) != null) {
                throw new IllegalStateException("Duplicate translation provider");
            }
        }
        return Map.copyOf(byId);
    }

    private void validateModelProviders() {
        for (var model : models.values()) {
            if (!providers.containsKey(model.provider())) {
                throw new IllegalStateException("Model references an unavailable provider");
            }
        }
    }

    public TranslatorProperties.Model select(String alias, Authentication authentication) {
        var model = models.get(alias);
        if (model == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "UNKNOWN_MODEL", "The selected model is unavailable.");
        }
        if (!allowed(model, authentication)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "MODEL_FORBIDDEN", "The selected model is not allowed.");
        }
        return model;
    }

    public TranslationProvider provider(TranslatorProperties.Model model) {
        return providers.get(model.provider());
    }

    public List<ModelView> available(Authentication authentication) {
        return models.entrySet().stream()
                .filter(entry -> allowed(entry.getValue(), authentication))
                .sorted(Map.Entry.comparingByKey())
                .map(
                        entry ->
                                new ModelView(
                                        entry.getKey(),
                                        entry.getValue().provider(),
                                        entry.getValue().model(),
                                        entry.getValue().maxInputBytes()))
                .toList();
    }

    private boolean allowed(TranslatorProperties.Model model, Authentication authentication) {
        String scope = model.requiredScope();
        if (scope == null || scope.isBlank()) {
            return true;
        }
        String requiredAuthority = "SCOPE_" + scope;
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
    }

    public record ModelView(String id, String provider, String name, int maxInputBytes) {}
}

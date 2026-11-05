package dev.translator.translation;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ModelRegistry {
    private final Map<String, TranslatorProperties.Model> models;
    private final Map<String, TranslationProvider> providers;

    public ModelRegistry(TranslatorProperties properties, List<TranslationProvider> providers) {
        this.models = Map.copyOf(properties.models());
        var byId = new HashMap<String, TranslationProvider>();
        for (var provider : providers) {
            if (byId.put(provider.id(), provider) != null) throw new IllegalStateException("Duplicate translation provider");
        }
        this.providers = Map.copyOf(byId);
        models.values().forEach(model -> {
            if (!byId.containsKey(model.provider())) throw new IllegalStateException("Model references an unavailable provider");
        });
    }

    public TranslatorProperties.Model select(String alias, Authentication authentication) {
        var model = models.get(alias);
        if (model == null) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_MODEL", "The selected model is unavailable.");
        if (!allowed(model, authentication)) throw new ApiException(HttpStatus.FORBIDDEN, "MODEL_FORBIDDEN", "The selected model is not allowed.");
        return model;
    }

    public TranslationProvider provider(TranslatorProperties.Model model) { return providers.get(model.provider()); }

    public List<ModelView> available(Authentication authentication) {
        return models.entrySet().stream().filter(entry -> allowed(entry.getValue(), authentication))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ModelView(entry.getKey(), entry.getValue().provider(), entry.getValue().maxInputBytes()))
                .toList();
    }

    private boolean allowed(TranslatorProperties.Model model, Authentication authentication) {
        return model.requiredScope() == null || model.requiredScope().isBlank()
                || authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("SCOPE_" + model.requiredScope()));
    }

    public record ModelView(String id, String provider, int maxInputBytes) {}
}

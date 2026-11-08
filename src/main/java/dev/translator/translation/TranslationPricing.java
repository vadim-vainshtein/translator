package dev.translator.translation;

import dev.translator.translation.TranslationProvider.ProviderUsage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Map;

@Validated
@ConfigurationProperties("translator.pricing")
public record TranslationPricing(Map<String, @Valid Rates> models) {
    public TranslationPricing {
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public BigDecimal costUsd(String model, ProviderUsage usage) {
        Rates rates = models.get(model);
        if (rates == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return null;
        }
        long cached = usage.cachedInputTokens() == null ? 0 : usage.cachedInputTokens();
        long uncached = usage.inputTokens() - cached;
        return rates.inputUsdPerMillion().multiply(BigDecimal.valueOf(uncached))
                .add(rates.cachedInputUsdPerMillion().multiply(BigDecimal.valueOf(cached)))
                .add(rates.outputUsdPerMillion().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .movePointLeft(6);
    }

    public record Rates(
            @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 6) BigDecimal inputUsdPerMillion,
            @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 6) BigDecimal cachedInputUsdPerMillion,
            @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 6) BigDecimal outputUsdPerMillion) {}
}

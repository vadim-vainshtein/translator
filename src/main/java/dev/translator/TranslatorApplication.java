package dev.translator;

import dev.translator.config.TranslatorProperties;
import dev.translator.translation.TranslationPricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TranslatorProperties.class, TranslationPricing.class})
public class TranslatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TranslatorApplication.class, args);
    }
}

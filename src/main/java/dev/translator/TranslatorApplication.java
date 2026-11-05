package dev.translator;

import dev.translator.config.TranslatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TranslatorProperties.class)
public class TranslatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TranslatorApplication.class, args);
    }
}

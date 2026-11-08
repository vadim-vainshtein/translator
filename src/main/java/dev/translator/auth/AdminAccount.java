package dev.translator.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
class AdminAccount implements ApplicationRunner {
    // A legacy account named admin must never gain privileges through its username.
    static final UUID ID = UUID.fromString("d93f4879-c7e8-4248-a68f-62a44f8ea818");
    static final String USERNAME = "admin";

    private final AuthRepository repository;
    private final PasswordEncoder passwords;
    private final ConfigurableEnvironment environment;

    AdminAccount(
            AuthRepository repository,
            PasswordEncoder passwords,
            ConfigurableEnvironment environment) {
        this.repository = repository;
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String password = configuredPassword();
        var existing = repository.findUser(USERNAME);
        if (existing == null) {
            if (repository.createUser(ID, USERNAME, passwords.encode(password))) {
                return;
            }
            existing = repository.findUser(USERNAME);
        }
        if (existing == null || !ID.equals(existing.id())) {
            throw new IllegalStateException("The username admin belongs to an existing account.");
        }
    }

    private String configuredPassword() {
        // Read the raw value so placeholder or expression syntax in the password stays literal.
        for (var source : environment.getPropertySources()) {
            if (source.getProperty("ADMIN_PASSWORD") instanceof String password) {
                if (!password.isBlank()
                        && password.length() >= 12
                        && password.getBytes(StandardCharsets.UTF_8).length <= 72) {
                    return password;
                }
                break;
            }
        }
        throw new IllegalStateException(
                "ADMIN_PASSWORD must be nonblank, at least 12 characters and at most 72 UTF-8 bytes.");
    }
}

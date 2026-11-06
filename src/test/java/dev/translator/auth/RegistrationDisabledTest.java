package dev.translator.auth;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationDisabledTest {
    @Test void disabledRegistrationCreatesNeitherAccountsNorSessions() {
        var jdbc = mock(JdbcTemplate.class);
        var passwords = mock(PasswordEncoder.class);
        var encoder = mock(JwtEncoder.class);
        var security = new TranslatorProperties.Security("translator", "translator-api", "file:unused",
                Duration.ofMinutes(15), Duration.ofDays(30), 30, false);
        var service = new AuthService(jdbc, passwords, encoder, new TranslatorProperties(security, null, null, null, null),
                mock(AuthRateLimiter.class), mock(PlatformTransactionManager.class));
        assertThatThrownBy(() -> service.register("alice", "correct horse battery"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("REGISTRATION_DISABLED");
                });
        verifyNoInteractions(jdbc, encoder);
    }
}

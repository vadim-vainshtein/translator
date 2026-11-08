package dev.translator.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.stream.Stream;

class AdminAccountTest {
    private static final String ADMIN_PASSWORD = "test-only-admin-password";
    private final AuthRepository repository = mock(AuthRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    @Test
    void bootstrapsAdminWithTheConfiguredPasswordHash() {
        String literalPassword = "${unresolved}#{expression}";
        when(passwords.encode(literalPassword)).thenReturn("hash");
        when(repository.createUser(AdminAccount.ID, "admin", "hash")).thenReturn(true);

        bootstrap(literalPassword);

        verify(repository).findUser("admin");
        verify(repository).createUser(AdminAccount.ID, "admin", "hash");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void preservesTheExistingAdminCredentialsOnRestart() {
        when(repository.findUser("admin"))
                .thenReturn(new AuthRepository.User(AdminAccount.ID, "existing-hash"));

        bootstrap("other-admin-password");

        verify(repository).findUser("admin");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(passwords);
    }

    @Test
    void acceptsAdminCreatedByAnotherInstance() {
        when(passwords.encode(ADMIN_PASSWORD)).thenReturn("hash");
        when(repository.findUser("admin"))
                .thenReturn(null, new AuthRepository.User(AdminAccount.ID, "other-hash"));

        bootstrap(ADMIN_PASSWORD);

        verify(repository).createUser(AdminAccount.ID, "admin", "hash");
    }

    @Test
    void refusesToTakeOverAnExistingUserNamedAdmin() {
        when(repository.findUser("admin"))
                .thenReturn(new AuthRepository.User(UUID.randomUUID(), "existing-hash"));

        assertThatThrownBy(() -> bootstrap(ADMIN_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username admin");
        verify(repository).findUser("admin");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(passwords);
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsInvalidPasswordsBeforeTouchingTheDatabase(String password) {
        assertThatThrownBy(() -> bootstrap(password))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("ADMIN_PASSWORD must");
        verifyNoInteractions(repository, passwords);
    }

    private static Stream<String> invalidPasswords() {
        return Stream.of("", " ".repeat(12), "short", "界".repeat(25));
    }

    private void bootstrap(String password) {
        var environment = new MockEnvironment().withProperty("ADMIN_PASSWORD", password);
        new AdminAccount(repository, passwords, environment).run(null);
    }
}

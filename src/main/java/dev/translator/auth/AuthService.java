package dev.translator.auth;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder passwords;
    private final TokenIssuer tokens;
    private final TranslatorProperties.Security security;
    private final String dummyPassword;
    private final AuthRateLimiter limiter;
    private final TransactionTemplate transactions;

    public AuthService(
            AuthRepository repository,
            PasswordEncoder passwords,
            TokenIssuer tokens,
            TranslatorProperties properties,
            AuthRateLimiter limiter,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.passwords = passwords;
        this.tokens = tokens;
        this.security = properties.security();
        this.dummyPassword = passwords.encode(UUID.randomUUID().toString());
        this.limiter = limiter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public void register(String username, String password) {
        validatePassword(password);
        String name = canonical(username);
        if (AdminAccount.USERNAME.equals(name)
                || !repository.createUser(UUID.randomUUID(), name, passwords.encode(password))) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "USERNAME_UNAVAILABLE", "Username is unavailable.");
        }
    }

    public Tokens login(String username, String password, String clientAddress) {
        validatePassword(password);
        var user = repository.findUser(canonical(username));
        // Unrelated usernames at one proxy/NAT cannot consume an existing identity's limit.
        // Unknown names share an address bucket rather than creating unbounded buckets.
        limiter.admit(user == null ? "missing-login:" + clientAddress : "login:" + user.id());
        String expected = user == null ? dummyPassword : user.passwordHash();
        boolean matches = passwords.matches(password, expected);
        if (!matches || user == null) {
            throw unauthorized("Invalid username or password.");
        }
        return transactions.execute(status -> newSession(user.id()));
    }

    // Replay revocation must commit even though the request returns 401.
    @Transactional(noRollbackFor = ApiException.class)
    public Tokens refresh(String refreshToken) {
        var family = repository.lockFamily(refreshToken);
        if (family == null) {
            throw unauthorized("Invalid refresh token.");
        }
        OffsetDateTime now = repository.now();
        requireActiveFamily(family, now);
        revokeReplayedToken(refreshToken, family, now);
        limiter.admitSession(family.id());
        repository.consume(refreshToken, now);
        return tokens.issue(family.user(), family.id(), now, family.expires());
    }

    @Transactional
    public void logout(String refreshToken) {
        var family = repository.lockFamily(refreshToken);
        if (family != null) {
            repository.revoke(family.id(), repository.now());
        }
    }

    private void requireActiveFamily(AuthRepository.Family family, OffsetDateTime now) {
        if (family.revoked() || !family.expires().isAfter(now)) {
            throw unauthorized("Invalid refresh token.");
        }
    }

    private void revokeReplayedToken(
            String refreshToken, AuthRepository.Family family, OffsetDateTime now) {
        if (repository.isConsumed(refreshToken)) {
            repository.revoke(family.id(), now);
            throw unauthorized("Invalid refresh token.");
        }
    }

    private Tokens newSession(UUID user) {
        UUID family = UUID.randomUUID();
        OffsetDateTime now = repository.now();
        OffsetDateTime expires = now.plus(security.refreshTokenTtl());
        repository.createFamily(family, user, expires);
        return tokens.issue(user, family, now, expires);
    }

    private static String canonical(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "Password must not exceed 72 UTF-8 bytes.");
        }
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", message);
    }

    public record Tokens(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn) {
        @Override
        public String toString() {
            return "Tokens[REDACTED]";
        }
    }
}

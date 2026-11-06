package dev.translator.auth;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final TranslatorProperties.Security security;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPassword;
    private final AuthRateLimiter limiter;
    private final TransactionTemplate transactions;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder, TranslatorProperties properties,
                       AuthRateLimiter limiter, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        security = properties.security();
        dummyPassword = passwords.encode(UUID.randomUUID().toString());
        this.limiter = limiter;
        transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Tokens register(String username, String password) {
        if (!security.registrationEnabled())
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED", "Public registration is disabled.");
        validatePassword(password);
        UUID user = UUID.randomUUID();
        int inserted = jdbc.update("INSERT INTO auth_user(user_id, username, password_hash) VALUES (?, ?, ?) ON CONFLICT (username) DO NOTHING",
                user, canonical(username), passwords.encode(password));
        if (inserted == 0) throw new ApiException(HttpStatus.CONFLICT, "USERNAME_UNAVAILABLE", "Username is unavailable.");
        return newSession(user);
    }

    public Tokens login(String username, String password, String clientAddress) {
        validatePassword(password);
        var users = jdbc.queryForList("SELECT user_id, password_hash FROM auth_user WHERE username = ?", canonical(username));
        // Existing identities cannot be denied by failures against unrelated usernames
        // on the same proxy/NAT address. Unknown names do not create unbounded buckets.
        limiter.admit(users.isEmpty() ? "missing-login:" + clientAddress : "login:" + users.getFirst().get("user_id"));
        String expected = users.isEmpty() ? dummyPassword : (String) users.getFirst().get("password_hash");
        boolean matches = passwords.matches(password, expected);
        if (!matches || users.isEmpty()) throw unauthorized("Invalid username or password.");
        return transactions.execute(status -> newSession((UUID) users.getFirst().get("user_id")));
    }

    // Replay revocation must commit even though the request returns 401.
    @Transactional(noRollbackFor = ApiException.class)
    public Tokens refresh(String refreshToken) {
        var family = lockFamily(refreshToken);
        if (family == null) throw unauthorized("Invalid refresh token.");
        UUID familyId = family.id();
        OffsetDateTime now = now();
        if (family.revoked() || !family.expires().isAfter(now)) {
            throw unauthorized("Invalid refresh token.");
        }
        var token = jdbc.queryForMap("SELECT consumed_at FROM auth_refresh_token WHERE token_hash = ?", hash(refreshToken));
        if (token.get("consumed_at") != null) {
            revoke(familyId, now);
            throw unauthorized("Invalid refresh token.");
        }
        limiter.admitSession(familyId);
        jdbc.update("UPDATE auth_refresh_token SET consumed_at = ? WHERE token_hash = ?", now, hash(refreshToken));
        return issue(family.user(), familyId, now, family.expires());
    }

    @Transactional
    public void logout(String refreshToken) {
        var family = lockFamily(refreshToken);
        if (family != null) revoke(family.id(), now());
    }

    private Family lockFamily(String refreshToken) {
        var ids = jdbc.queryForList("SELECT family_id FROM auth_refresh_token WHERE token_hash = ?", UUID.class, hash(refreshToken));
        if (ids.isEmpty()) return null;
        // Read token state only after this shared family lock is acquired, so rotation and logout serialize.
        return jdbc.queryForObject("SELECT * FROM auth_refresh_family WHERE family_id = ? FOR UPDATE",
                (row, number) -> new Family(row.getObject("family_id", UUID.class), row.getObject("user_id", UUID.class),
                        row.getObject("expires_at", OffsetDateTime.class), row.getObject("revoked_at") != null), ids.getFirst());
    }

    private Tokens newSession(UUID user) {
        UUID family = UUID.randomUUID();
        OffsetDateTime now = now();
        OffsetDateTime expires = now.plus(security.refreshTokenTtl());
        jdbc.update("INSERT INTO auth_refresh_family(family_id, user_id, expires_at) VALUES (?, ?, ?)", family, user, expires);
        return issue(user, family, now, expires);
    }

    private Tokens issue(UUID user, UUID family, OffsetDateTime now, OffsetDateTime refreshExpires) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO auth_refresh_token(token_hash, family_id) VALUES (?, ?)", hash(refreshToken), family);
        Instant issued = now.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expires = issued.plus(security.accessTokenTtl());
        var claims = JwtClaimsSet.builder().issuer(security.issuer()).subject(user.toString())
                .audience(List.of(security.audience())).issuedAt(issued).expiresAt(expires)
                .id(UUID.randomUUID().toString()).claim("scope", "translate:write").build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Tokens(accessToken, "Bearer", Duration.between(issued, expires).toSeconds(), refreshToken,
                Math.max(0, Duration.between(now, refreshExpires).toSeconds()));
    }

    private void revoke(UUID family, OffsetDateTime now) {
        jdbc.update("UPDATE auth_refresh_family SET revoked_at = coalesce(revoked_at, ?) WHERE family_id = ?", now, family);
    }
    private OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    private static String canonical(String username) { return username.toLowerCase(Locale.ROOT); }
    private static void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Password must not exceed 72 UTF-8 bytes.");
        }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static ApiException unauthorized(String message) { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", message); }

    public record Tokens(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {
        @Override public String toString() { return "Tokens[REDACTED]"; }
    }
    private record Family(UUID id, UUID user, OffsetDateTime expires, boolean revoked) {}
}

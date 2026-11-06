package dev.translator.auth;

import dev.translator.config.TranslatorProperties;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class TokenIssuer {
    private final JwtEncoder encoder;
    private final AuthRepository repository;
    private final TranslatorProperties.Security security;
    private final SecureRandom random = new SecureRandom();

    public TokenIssuer(
            JwtEncoder encoder, AuthRepository repository, TranslatorProperties properties) {
        this.encoder = encoder;
        this.repository = repository;
        this.security = properties.security();
    }

    public AuthService.Tokens issue(
            UUID user, UUID family, OffsetDateTime now, OffsetDateTime refreshExpires) {
        String refreshToken = newRefreshToken();
        repository.storeRefreshToken(refreshToken, family);
        Instant issued = now.toInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plus(security.accessTokenTtl());
        String accessToken = accessToken(user, issued, expires);
        return new AuthService.Tokens(
                accessToken,
                "Bearer",
                Duration.between(issued, expires).toSeconds(),
                refreshToken,
                Math.max(0, Duration.between(now, refreshExpires).toSeconds()));
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String accessToken(UUID user, Instant issued, Instant expires) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(security.issuer())
                        .subject(user.toString())
                        .audience(List.of(security.audience()))
                        .issuedAt(issued)
                        .expiresAt(expires)
                        .id(UUID.randomUUID().toString())
                        .claim("scope", "translate:write")
                        .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

package dev.translator.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    RSAKey signingKey(TranslatorProperties properties, ResourceLoader resources) throws Exception {
        var privateKey = loadPrivateKey(properties.security().privateKeyLocation(), resources);
        var publicKey = publicKey(privateKey);
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    }

    private RSAPrivateCrtKey loadPrivateKey(String location, ResourceLoader resources)
            throws Exception {
        try (var input = resources.getResource(location).getInputStream()) {
            var key = (RSAPrivateCrtKey) RsaKeyConverters.pkcs8().convert(input);
            if (key == null || key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException(
                        "JWT signing key must be an RSA PKCS#8 private key of at least 2048 bits.");
            }
            return key;
        }
    }

    private RSAPublicKey publicKey(RSAPrivateCrtKey privateKey) throws Exception {
        var specification =
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(specification);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtDecoder jwtDecoder(TranslatorProperties properties, RSAKey key) throws Exception {
        var security = properties.security();
        var decoder =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        List.of(
                                new JwtTimestampValidator(Duration.ZERO),
                                new JwtIssuerValidator(security.issuer()),
                                claimsValidator(security.audience()))));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> claimsValidator(String audience) {
        return jwt -> {
            if (hasRequiredClaims(jwt, audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required token claims are invalid.", null));
        };
    }

    private boolean hasRequiredClaims(Jwt jwt, String audience) {
        return jwt.getExpiresAt() != null
                && jwt.getSubject() != null
                && !jwt.getSubject().isBlank()
                && jwt.getAudience() != null
                && jwt.getAudience().contains(audience);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/",
                                                "/index.html",
                                                "/app.css",
                                                "/app.js")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/v1/auth/register")
                                        .hasAuthority("SCOPE_users:create")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/v1/auth/login",
                                                "/v1/auth/refresh",
                                                "/v1/auth/logout")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/v1/translations")
                                        .hasAuthority("SCOPE_translate:write")
                                        .requestMatchers(HttpMethod.GET, "/v1/models", "/v1/translation-cost")
                                        .hasAuthority("SCOPE_translate:write")
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }
}

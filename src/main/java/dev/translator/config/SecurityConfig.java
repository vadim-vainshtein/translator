package dev.translator.config;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    RSAKey signingKey(TranslatorProperties properties, ResourceLoader resources) throws Exception {
        try (var input = resources.getResource(properties.security().privateKeyLocation()).getInputStream()) {
            var privateKey = (RSAPrivateCrtKey) RsaKeyConverters.pkcs8().convert(input);
            if (privateKey == null || privateKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("JWT signing key must be an RSA PKCS#8 private key of at least 2048 bits.");
            }
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) { return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    JwtDecoder jwtDecoder(TranslatorProperties properties, RSAKey key) throws Exception {
        var security = properties.security();
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                    || jwt.getAudience() == null || !jwt.getAudience().contains(security.audience())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required token claims are invalid.", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(security.issuer()), claims)));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/translations").hasAuthority("SCOPE_translate:write")
                        .requestMatchers(HttpMethod.GET, "/v1/models").hasAuthority("SCOPE_translate:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }
}

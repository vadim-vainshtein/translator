package dev.translator.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(TranslatorProperties properties) {
        var security = properties.security();
        var decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                    || jwt.getAudience() == null || !jwt.getAudience().contains(security.audience())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required token claims are invalid.", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(JwtValidators.createDefaultWithIssuer(security.issuer()), claims)));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/translations").hasAuthority("SCOPE_translate:write")
                        .requestMatchers(HttpMethod.GET, "/v1/models").hasAuthority("SCOPE_translate:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }
}

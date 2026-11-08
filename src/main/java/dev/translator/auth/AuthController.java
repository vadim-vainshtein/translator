package dev.translator.auth;

import dev.translator.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter limiter;

    public AuthController(AuthService auth, AuthRateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> register(@Valid @RequestBody Credentials body) {
        auth.register(body.username(), body.password());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthService.Tokens> login(
            @Valid @RequestBody Credentials body, HttpServletRequest request) {
        return tokens(
                HttpStatus.OK,
                auth.login(body.username(), body.password(), request.getRemoteAddr()));
    }

    @PostMapping(value = "/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest body, JwtAuthenticationToken authentication) {
        auth.changePassword(
                authentication.getToken().getSubject(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthService.Tokens> refresh(
            @Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        try {
            return tokens(HttpStatus.OK, auth.refresh(body.refreshToken()));
        } catch (ApiException exception) {
            if (exception.status() == HttpStatus.UNAUTHORIZED) {
                limiter.admit("invalid-session:" + request.getRemoteAddr());
            }
            throw exception;
        }
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        // Revocation must remain available even when anonymous authentication buckets are
        // exhausted.
        auth.logout(body.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<AuthService.Tokens> tokens(
            HttpStatus status, AuthService.Tokens tokens) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(tokens);
    }

    public record Credentials(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,64}") String username,
            @NotNull @Size(min = 12, max = 72) String password) {
        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }

    public record ChangePasswordRequest(
            @NotNull @Size(min = 12, max = 72) String currentPassword,
            @NotNull @Size(min = 12, max = 72) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[REDACTED]";
        }
    }

    public record RefreshRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[REDACTED]";
        }
    }
}

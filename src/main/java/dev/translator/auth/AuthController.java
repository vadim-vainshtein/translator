package dev.translator.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter limiter;
    private final boolean registrationEnabled;

    public AuthController(AuthService auth, AuthRateLimiter limiter, TranslatorProperties properties) {
        this.auth = auth; this.limiter = limiter;
        registrationEnabled = properties.security().registrationEnabled();
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthService.Tokens> register(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        if (!registrationEnabled)
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED", "Public registration is disabled.");
        limiter.admit("signup:" + request.getRemoteAddr());
        return tokens(HttpStatus.CREATED, auth.register(body.username(), body.password()));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthService.Tokens> login(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        return tokens(HttpStatus.OK, auth.login(body.username(), body.password(), request.getRemoteAddr()));
    }

    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthService.Tokens> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        try { return tokens(HttpStatus.OK, auth.refresh(body.refreshToken())); }
        catch (ApiException exception) {
            if (exception.status() == HttpStatus.UNAUTHORIZED) limiter.admit("invalid-session:" + request.getRemoteAddr());
            throw exception;
        }
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        // Revocation must remain available even when anonymous authentication buckets are exhausted.
        auth.logout(body.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<AuthService.Tokens> tokens(HttpStatus status, AuthService.Tokens tokens) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(tokens);
    }

    public record Credentials(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,64}") String username,
                              @NotNull @Size(min = 12, max = 72) String password) {
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    public record RefreshRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override public String toString() { return "RefreshRequest[REDACTED]"; }
    }
}

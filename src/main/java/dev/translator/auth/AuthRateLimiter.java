package dev.translator.auth;

import dev.translator.api.ApiException;
import dev.translator.config.TranslatorProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final int limit;

    public AuthRateLimiter(JdbcTemplate jdbc, TranslatorProperties properties) {
        this.jdbc = jdbc;
        limit = properties.security().authRequestsPerMinute();
    }

    // Separate transaction: failed authentication must still count against the limit.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void admit(String bucket) { increment(bucket); }

    // Join refresh's transaction; a nested REQUIRES_NEW while holding a family lock
    // could exhaust the connection pool under concurrent refreshes.
    @Transactional(noRollbackFor = ApiException.class)
    public void admitSession(UUID family) { increment("session:" + family); }

    private void increment(String bucket) {
        jdbc.update("DELETE FROM auth_rate_limit WHERE window_started_at < clock_timestamp() - interval '1 day'");
        int attempts = jdbc.queryForObject("""
                INSERT INTO auth_rate_limit(client_hash, window_started_at, attempts)
                VALUES (?, clock_timestamp(), 1)
                ON CONFLICT (client_hash) DO UPDATE SET
                    attempts = CASE WHEN auth_rate_limit.window_started_at <= clock_timestamp() - interval '1 minute'
                                    THEN 1 ELSE least(auth_rate_limit.attempts, ?) + 1 END,
                    window_started_at = CASE WHEN auth_rate_limit.window_started_at <= clock_timestamp() - interval '1 minute'
                                             THEN clock_timestamp() ELSE auth_rate_limit.window_started_at END
                RETURNING attempts
                """, Integer.class, AuthService.hash(bucket), limit);
        if (attempts > limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMIT", "Too many authentication attempts.");
    }
}

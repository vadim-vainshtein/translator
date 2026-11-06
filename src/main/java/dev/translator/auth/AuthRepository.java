package dev.translator.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean createUser(UUID user, String username, String passwordHash) {
        return jdbc.update(
                        """
                        INSERT INTO auth_user(user_id, username, password_hash) VALUES (?, ?, ?)
                        ON CONFLICT (username) DO NOTHING
                        """,
                        user,
                        username,
                        passwordHash)
                != 0;
    }

    public User findUser(String username) {
        var users =
                jdbc.query(
                        """
                        SELECT user_id, password_hash FROM auth_user WHERE username = ?
                        """,
                        (row, number) ->
                                new User(
                                        row.getObject("user_id", UUID.class),
                                        row.getString("password_hash")),
                        username);
        return users.isEmpty() ? null : users.getFirst();
    }

    public Family lockFamily(String refreshToken) {
        var ids =
                jdbc.queryForList(
                        """
                        SELECT family_id FROM auth_refresh_token WHERE token_hash = ?
                        """,
                        UUID.class,
                        TokenHash.sha256(refreshToken));
        if (ids.isEmpty()) {
            return null;
        }
        // Read token state only after this shared lock, so rotation and logout serialize.
        return jdbc.queryForObject(
                """
                SELECT * FROM auth_refresh_family WHERE family_id = ? FOR UPDATE
                """,
                (row, number) ->
                        new Family(
                                row.getObject("family_id", UUID.class),
                                row.getObject("user_id", UUID.class),
                                row.getObject("expires_at", OffsetDateTime.class),
                                row.getObject("revoked_at") != null),
                ids.getFirst());
    }

    public boolean isConsumed(String refreshToken) {
        var token =
                jdbc.queryForMap(
                        """
                        SELECT consumed_at FROM auth_refresh_token WHERE token_hash = ?
                        """,
                        TokenHash.sha256(refreshToken));
        return token.get("consumed_at") != null;
    }

    public void consume(String refreshToken, OffsetDateTime now) {
        jdbc.update(
                """
                UPDATE auth_refresh_token SET consumed_at = ? WHERE token_hash = ?
                """,
                now,
                TokenHash.sha256(refreshToken));
    }

    public void createFamily(UUID family, UUID user, OffsetDateTime expires) {
        jdbc.update(
                """
                INSERT INTO auth_refresh_family(family_id, user_id, expires_at) VALUES (?, ?, ?)
                """,
                family,
                user,
                expires);
    }

    public void storeRefreshToken(String refreshToken, UUID family) {
        jdbc.update(
                """
                INSERT INTO auth_refresh_token(token_hash, family_id) VALUES (?, ?)
                """,
                TokenHash.sha256(refreshToken),
                family);
    }

    public void revoke(UUID family, OffsetDateTime now) {
        jdbc.update(
                """
                UPDATE auth_refresh_family SET revoked_at = coalesce(revoked_at, ?)
                WHERE family_id = ?
                """,
                now,
                family);
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    public record User(UUID id, String passwordHash) {}

    public record Family(UUID id, UUID user, OffsetDateTime expires, boolean revoked) {}
}

package nl.hackyourfuture.project.backend.identity.token;

import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, redeems and revokes the refresh tokens {@code identity} hands out (Day 12): 32 random
 * bytes, base64url, good for thirty days unless revoked. Only the token's SHA-256 is stored, so a
 * copy of the table signs no one in.
 *
 * <p>Nothing calls this yet. Day 13 issues one at login, rotates it on refresh and revokes it at
 * logout; until then no response changes.
 */
@Component
public class RefreshTokens {

    public static final Duration LIFETIME = Duration.ofDays(30);

    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbcClient;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokens(@Qualifier("identityJdbcClient") JdbcClient jdbcClient, UserRepository users) {
        this.jdbcClient = jdbcClient;
        this.users = users;
    }

    /** A new token for this user. The caller holds the only copy of it. */
    public String issue(UUID userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // One now() for both columns, so the lifetime is exact.
        jdbcClient.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, created_at, expires_at)
                        VALUES (:userId, :tokenHash, now(), now() + make_interval(secs => :lifetime))
                        """)
                .param("userId", userId)
                .param("tokenHash", hash(token))
                .param("lifetime", LIFETIME.toSeconds())
                .update();
        return token;
    }

    /** The user a live token belongs to; nothing for a revoked, expired or unknown one. */
    public Optional<User> redeem(String token) {
        return jdbcClient.sql("""
                        SELECT user_id FROM refresh_tokens
                        WHERE token_hash = :tokenHash AND revoked_at IS NULL AND expires_at > now()
                        """)
                .param("tokenHash", hash(token))
                .query(UUID.class)
                .optional()
                .flatMap(users::findById);
    }

    /** Stops a token working. Revoking one that is unknown or already revoked does nothing. */
    public void revoke(String token) {
        jdbcClient.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = :tokenHash AND revoked_at IS NULL")
                .param("tokenHash", hash(token))
                .update();
    }

    /** The SHA-256 of the token as the client holds it, in lower-case hex. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}

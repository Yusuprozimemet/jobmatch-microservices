package nl.hackyourfuture.project.backend.identity.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.hackyourfuture.project.backend.identity.token.RefreshTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A Google identity whose email belongs to an existing account, parked until that account's
 * password login proves it is theirs (Day 14). The browser holds the code in a cookie and sends it
 * to the login by itself; the code is 32 random bytes, stored only as its SHA-256.
 */
@Component
public class PendingGoogleLinks {

    public static final String COOKIE = "pending_google_link";
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    // Where the password login is; nothing else needs the code.
    static final String PATH = "/api/auth";

    private static final int CODE_BYTES = 32;

    private final JdbcClient jdbcClient;
    private final boolean secure;
    private final SecureRandom random = new SecureRandom();

    // Secure as the session cookie that carried this link before (SESSION_COOKIE_SECURE).
    public PendingGoogleLinks(@Qualifier("identityJdbcClient") JdbcClient jdbcClient,
                              @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.jdbcClient = jdbcClient;
        this.secure = secure;
    }

    /** Parks this Google identity for the account, in place of any earlier one. The caller holds the only copy of the code. */
    public String save(UUID userId, String providerId) {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // One statement, so no moment has two live links, and one now() for the exact lifetime.
        jdbcClient.sql("""
                        WITH earlier AS (DELETE FROM pending_google_links WHERE user_id = :userId)
                        INSERT INTO pending_google_links (user_id, provider_id, code_hash, created_at, expires_at)
                        VALUES (:userId, :providerId, :codeHash, now(), now() + make_interval(secs => :lifetime))
                        """)
                .param("userId", userId)
                .param("providerId", providerId)
                .param("codeHash", RefreshTokens.hash(code))
                .param("lifetime", LIFETIME.toSeconds())
                .update();
        return code;
    }

    /**
     * The Google subject parked under this code for this account, claimed in the same statement so
     * two racing logins cannot both get it. Nothing for another account's, claimed, expired or unknown code.
     */
    public Optional<String> claim(String code, UUID userId) {
        return jdbcClient.sql("""
                        UPDATE pending_google_links SET claimed_at = now()
                        WHERE code_hash = :codeHash AND user_id = :userId
                          AND claimed_at IS NULL AND expires_at > now()
                        RETURNING provider_id
                        """)
                .param("codeHash", RefreshTokens.hash(code))
                .param("userId", userId)
                .query(String.class)
                .optional();
    }

    /** Parks the identity and hands the browser the code. */
    void park(HttpServletResponse response, UUID userId, String providerId) {
        addCookie(response, save(userId, providerId), LIFETIME);
    }

    /** Claims the browser's link for the account that proved its password, and deletes the cookie if it did. */
    Optional<String> claim(HttpServletRequest request, HttpServletResponse response, UUID userId) {
        Optional<String> providerId = Optional.ofNullable(request.getCookies()).stream()
                .flatMap(Arrays::stream)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst()
                .flatMap(code -> claim(code, userId));
        providerId.ifPresent(claimed -> addCookie(response, "", Duration.ZERO));
        return providerId;
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

package nl.hackyourfuture.project.backend.identity.token;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Puts a signed-in user's tokens in the browser (Day 13): the access token for every route, and
 * the refresh token only where it is used, under {@code /api/auth}.
 *
 * <p>Both are {@code HttpOnly}, so page scripts cannot read them, and {@code SameSite=Lax}, so
 * other sites cannot send them with a form post. Each lives as long as its token.
 */
@Component
public class AuthCookies {

    public static final String ACCESS = "access_token";
    public static final String REFRESH = "refresh_token";

    // Not the refresh path alone: logout is under /api/auth too, and must receive the token to
    // revoke it. A browser sends a cookie only to paths under its own.
    static final String REFRESH_PATH = "/api/auth";

    private final AccessTokens accessTokens;
    private final RefreshTokens refreshTokens;

    public AuthCookies(AccessTokens accessTokens, RefreshTokens refreshTokens) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    /** A new access token and a new refresh token for this user, as cookies on the response. */
    public void issue(HttpServletResponse response, UUID userId, String email) {
        add(response, ACCESS, accessTokens.mint(userId, email), "/", AccessTokens.LIFETIME);
        add(response, REFRESH, refreshTokens.issue(userId), REFRESH_PATH, RefreshTokens.LIFETIME);
    }

    private static void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

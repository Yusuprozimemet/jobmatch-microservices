package nl.hackyourfuture.project.backend.identity.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.hackyourfuture.project.backend.identity.user.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
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

    /**
     * Trades the refresh cookie for a new pair. The old refresh token is revoked in the same
     * transaction as the new one is stored, so a failure in between leaves the old one working.
     *
     * @return whether the refresh token was live; if not, both cookies are deleted
     */
    @Transactional("identityTransactionManager")
    public boolean refresh(HttpServletRequest request, HttpServletResponse response) {
        Optional<User> user = refreshToken(request).flatMap(refreshTokens::rotate);
        if (user.isEmpty()) {
            clear(response);
            return false;
        }
        issue(response, user.get().getId(), user.get().getEmail());
        return true;
    }

    /** Signs the browser out: its refresh token no longer refreshes, and both cookies go. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        refreshToken(request).ifPresent(refreshTokens::revoke);
        clear(response);
    }

    private static Optional<String> refreshToken(HttpServletRequest request) {
        return Optional.ofNullable(request.getCookies()).stream()
                .flatMap(Arrays::stream)
                .filter(cookie -> REFRESH.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    /** Deletes both cookies: what signing out, however it happens, leaves in the browser. */
    public static void clear(HttpServletResponse response) {
        add(response, ACCESS, "", "/", Duration.ZERO);
        add(response, REFRESH, "", REFRESH_PATH, Duration.ZERO);
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

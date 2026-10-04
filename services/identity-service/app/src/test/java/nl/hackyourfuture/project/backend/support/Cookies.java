package nl.hackyourfuture.project.backend.support;

import java.net.HttpCookie;
import java.util.List;

/**
 * What these tests know about the application's cookies: the name of the one it authenticates
 * with, and how to tell that a {@code Set-Cookie} header deletes a cookie rather than sets one.
 *
 * <p>The name is a mechanism, not a contract. Until Day 13 it was the session cookie; since then it
 * is the access-token cookie. What the contract tests actually pin is
 * that logging in sets one cookie the browser sends back, that JavaScript cannot read it, that
 * other sites cannot send it, and that logging out deletes it. Every one of those assertions
 * takes the name from here, so Day 13 edited the line below and no assertion in
 * {@code contract/} changed.
 */
public final class Cookies {

    /** The cookie the application authenticates with. */
    public static final String AUTH = "access_token";

    private Cookies() {
    }

    /**
     * Whether this {@code Set-Cookie} header deletes its cookie instead of setting one.
     *
     * <p>Deletion has several spellings — an empty value, {@code Max-Age=0}, an {@code Expires}
     * in the past — and which one a server picks is its own business. Reading the header the way
     * a browser does keeps the assertion about the effect rather than the spelling, which is the
     * difference between a test that survives the auth rewrite and one that does not.
     */
    public static boolean deletes(String setCookieHeader) {
        List<HttpCookie> parsed = HttpCookie.parse(setCookieHeader);
        if (parsed.isEmpty()) {
            return false;
        }
        HttpCookie cookie = parsed.getFirst();
        return cookie.getValue().isEmpty() || cookie.hasExpired();
    }
}

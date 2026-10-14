package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.auth.AuthorizationRequestCookie;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.GoogleSignIn;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Google sign-in's authorization request travels from the start to the callback in a signed
 * cookie, not a session (Day 14). {@code AuthGoogleSignInIT} cannot see its attributes:
 * {@code ApiClient} ignores {@code Path}, {@code SameSite} and size, which a browser does not.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class AuthRequestCookieIT extends IntegrationTest {

    static final String FAILED = "http://localhost:3000/login?error=oauth";

    // What browsers are required to keep of one cookie (RFC 6265 §6.1), name and attributes included.
    private static final int BROWSER_LIMIT = 4096;

    @Test
    void theStartSetsItForTheCallbackAlone() {
        ApiResponse start = GoogleSignIn.start(anonymous(), "google-sub-attributes", "attributes@example.test", true);

        String header = start.setCookie(AuthorizationRequestCookie.COOKIE);
        assertThat(header).as("the cookie's attributes")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Path=/api/login/oauth2/code;")
                .containsIgnoringCase("Max-Age=300;")
                .doesNotContainIgnoringCase("Secure");
        assertThat(("Set-Cookie: " + header).getBytes(StandardCharsets.US_ASCII).length)
                .as("the whole header").isLessThan(BROWSER_LIMIT);
    }

    @Test
    void theCallbackDeletesIt() {
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-deleted", "deleted@example.test", true);

        ApiResponse callback = GoogleSignIn.callback(browser, start);

        assertThat(callback.location()).as("the sign-in went through").isNotEqualTo(FAILED);
        String deletion = callback.setCookie(AuthorizationRequestCookie.COOKIE);
        assertThat(Cookies.deletes(deletion)).as("deletes it: %s", deletion).isTrue();
        assertThat(HttpCookie.parse(deletion).getFirst().getPath()).as("under the path it was set on")
                .isEqualTo("/api/login/oauth2/code");
    }

    @Test
    void aCookieWithOneByteOfItsSignatureChangedFailsTheSignIn() {
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-tampered", "tampered@example.test", true);
        browser.withCookie(AuthorizationRequestCookie.COOKIE, tampered(browser.cookies().get(AuthorizationRequestCookie.COOKIE)));

        ApiResponse callback = GoogleSignIn.callback(browser, start);

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).isEqualTo(FAILED);
    }

    // The signature decoded, one byte flipped in its middle, encoded again: editing a base64
    // character instead could land on the final character's unused bits and change nothing.
    private static String tampered(String jws) {
        String[] parts = jws.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[signature.length / 2] ^= 1;
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return String.join(".", parts);
    }
}

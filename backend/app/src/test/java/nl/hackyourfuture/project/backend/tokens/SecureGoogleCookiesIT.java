package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.auth.PendingGoogleLinks;
import nl.hackyourfuture.project.backend.support.GoogleSignIn;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code SESSION_COOKIE_SECURE=true}, the cookies that carry the Google flow's state are
 * {@code Secure}, as the session cookie that carried it was (Day 14). Every such case goes here,
 * so the context this property needs starts once.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google",
        "server.servlet.session.cookie.secure=true"
})
class SecureGoogleCookiesIT extends IntegrationTest {

    @Test
    void thePendingLinkCookieIsSecure() {
        TestUser owner = aUser().email("secure@example.test").create();

        String header = GoogleSignIn.as(anonymous(), "google-sub-secure", owner.email()).setCookie(PendingGoogleLinks.COOKIE);

        assertThat(header).containsIgnoringCase("; Secure");
    }
}

package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.GoogleSignIn;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code SESSION_COOKIE_SECURE=true}, the two token cookies are {@code Secure} when they are
 * set and when they are deleted, as plan.md's Phase 2 asks and the Google flow's cookies already
 * are ({@code SecureGoogleCookiesIT}). The same configuration as that class, so the context this
 * property needs is the cached one, not a second.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google",
        "server.servlet.session.cookie.secure=true"
})
class SecureTokenCookiesIT extends IntegrationTest {

    @Test
    void theTokenCookiesAreSecure() {
        TestUser user = aUser().create();

        ApiResponse login = anonymous().post("/api/auth/login", Map.of("email", user.email(), "password", user.password()));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.setCookie("access_token")).containsIgnoringCase("; Secure");
        assertThat(login.setCookie("refresh_token")).containsIgnoringCase("; Secure");
    }

    @Test
    void logoutDeletesThemAsSecure() {
        ApiClient client = authenticatedAs(aUser().create());

        ApiResponse logout = client.post("/api/auth/logout", null);

        assertSecureDeletion(logout, "access_token");
        assertSecureDeletion(logout, "refresh_token");
    }

    @Test
    void deletingTheAccountDeletesThemAsSecure() {
        ApiResponse deleted = authenticatedAs(aUser().create()).delete("/api/users/me");

        assertThat(deleted.status()).isEqualTo(204);
        assertSecureDeletion(deleted, "access_token");
        assertSecureDeletion(deleted, "refresh_token");
    }

    private static void assertSecureDeletion(ApiResponse response, String name) {
        String header = response.setCookie(name);
        assertThat(Cookies.deletes(header)).as("deletes %s: %s", name, header).isTrue();
        assertThat(header).as("%s's deletion", name).containsIgnoringCase("; Secure");
    }
}

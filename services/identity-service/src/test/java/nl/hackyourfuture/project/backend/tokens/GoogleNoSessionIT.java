package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.GoogleSignIn;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No step of a Google sign-in sets a session cookie, on any of its paths (Day 14): the start, the
 * callback, and on the email-taken path the password login after it. {@code NoSessionIT} covers
 * every other route.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class GoogleNoSessionIT extends IntegrationTest {

    private static final String TERMS = "http://localhost:3000/accept-terms";
    private static final String NEEDS_LINK = "http://localhost:3000/login?error=google_link_required";
    private static final String FAILED = "http://localhost:3000/login?error=oauth";

    @Test
    void anIdentityAlreadyLinked() {
        GoogleSignIn.as(anonymous(), "google-sub-linked", "linked@example.test");
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-linked", "linked@example.test", true);
        ApiResponse callback = GoogleSignIn.callback(browser, start);

        assertThat(callback.location()).isEqualTo(TERMS);
        assertNoSession(List.of(start, callback));
    }

    @Test
    void aNewEmail() {
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-new", "new@example.test", true);
        ApiResponse callback = GoogleSignIn.callback(browser, start);

        assertThat(callback.location()).isEqualTo(TERMS);
        assertNoSession(List.of(start, callback));
    }

    @Test
    void anEmailTakenAndThePasswordLoginAfterIt() {
        TestUser owner = aUser().email("taken@example.test").create();
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-taken", owner.email(), true);
        ApiResponse callback = GoogleSignIn.callback(browser, start);
        ApiResponse login = browser.post("/api/auth/login", Map.of("email", owner.email(), "password", owner.password()));

        assertThat(callback.location()).isEqualTo(NEEDS_LINK);
        assertThat(login.status()).isEqualTo(200);
        assertNoSession(List.of(start, callback, login));
    }

    @Test
    void anUnverifiedEmail() {
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-unverified", "unverified@example.test", false);
        ApiResponse callback = GoogleSignIn.callback(browser, start);

        assertThat(callback.location()).isEqualTo(FAILED);
        assertNoSession(List.of(start, callback));
    }

    @Test
    void aForgedState() {
        ApiClient browser = anonymous();
        ApiResponse start = GoogleSignIn.start(browser, "google-sub-forged", "forged@example.test", true);
        ApiResponse callback = browser.get("/api/login/oauth2/code/google?code=stub-code&state=forged");

        assertThat(callback.location()).isEqualTo(FAILED);
        assertNoSession(List.of(start, callback));
    }

    private static void assertNoSession(List<ApiResponse> steps) {
        assertThat(steps).flatExtracting(ApiResponse::setCookieHeaders)
                .noneMatch(header -> header.startsWith("JSESSIONID="));
    }
}

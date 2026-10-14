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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Google identity parked for one account waits for that account's password, however many other
 * logins happen in the same browser first (Day 14, Track 0).
 *
 * <p>Written to hold before and after the pending link leaves the session. The contract suite
 * cannot tell: its wrong-account test signs in with Google again from a fresh browser, which lands
 * on the link-required page whether or not the wrong login used the link up.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class GoogleLinkClaimIT extends IntegrationTest {

    private static final String SIGNED_IN = "http://localhost:3000/";

    @Test
    void aLoginForAnotherAccountLeavesTheParkedLinkForItsOwner() {
        TestUser owner = aUser().email("owner@example.test").create();
        TestUser other = aUser().email("other@example.test").create();
        ApiClient browser = anonymous();
        GoogleSignIn.as(browser, "google-sub-owner", owner.email());

        assertThat(login(browser, other).status()).isEqualTo(200);
        assertThat(login(browser, owner).status()).isEqualTo(200);

        ApiClient later = anonymous();
        assertThat(GoogleSignIn.as(later, "google-sub-owner", owner.email()).location())
                .as("the owner's Google identity was linked by the owner's login")
                .isEqualTo(SIGNED_IN);
        // Landing on / is not enough: a link claimed by the other login would sign in as them.
        assertThat(later.get("/api/users/me").at("/email").asString()).isEqualTo(owner.email());
    }

    private static ApiResponse login(ApiClient browser, TestUser user) {
        return browser.post("/api/auth/login", Map.of("email", user.email(), "password", user.password()));
    }
}

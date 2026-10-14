package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.auth.PendingGoogleLinks;
import nl.hackyourfuture.project.backend.identity.token.RefreshTokens;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.GoogleSignIn;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.HttpCookie;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Google identity whose email is taken waits in {@code identity.pending_google_links}, not in a
 * session, for that account's password login (Day 14): once, within ten minutes, one per account.
 * Some cases call the bean: a second claim cannot be seen over HTTP, with nothing left to link.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class PendingGoogleLinksIT extends IntegrationTest {

    static final String NEEDS_LINK = "http://localhost:3000/login?error=google_link_required";

    @Autowired
    private PendingGoogleLinks links;

    @Test
    void isStoredOnlyAsItsSha256AndLastsTenMinutes() {
        TestUser user = aUser().create();
        String code = links.save(user.id(), "google-sub-stored");

        Map<String, Object> row = jdbc().sql("SELECT * FROM identity.pending_google_links").query().singleRow();
        assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(code));
        assertThat(row.get("code_hash")).as("RefreshTokensIT checks hash()").isEqualTo(RefreshTokens.hash(code));
        assertThat(jdbc().sql("SELECT extract(epoch FROM expires_at - created_at)::bigint FROM identity.pending_google_links")
                .query(Long.class)
                .single())
                .isEqualTo(600L);
    }

    @Test
    void claimsOnceAndOnlyForItsAccount() {
        TestUser owner = aUser().create();
        TestUser other = aUser().create();
        String code = links.save(owner.id(), "google-sub-once");

        assertThat(links.claim(code, other.id())).as("another account's claim").isEmpty();
        assertThat(links.claim(code, owner.id())).as("the first claim").contains("google-sub-once");
        assertThat(links.claim(code, owner.id())).as("the second claim").isEmpty();
    }

    @Test
    void aLoginWithAClaimedCodeLinksNothing() {
        TestUser owner = aUser().email("claimed@example.test").create();
        ApiClient browser = anonymous();
        GoogleSignIn.as(browser, "google-sub-claimed", owner.email());
        assertThat(links.claim(browser.cookies().get(PendingGoogleLinks.COOKIE), owner.id())).isPresent();

        assertThat(login(browser, owner).status()).isEqualTo(200);

        assertThat(GoogleSignIn.as(anonymous(), "google-sub-claimed", owner.email()).location()).isEqualTo(NEEDS_LINK);
    }

    @Test
    void aLinkOlderThanTenMinutesClaimsNothing() {
        TestUser owner = aUser().email("expired@example.test").create();
        ApiClient browser = anonymous();
        GoogleSignIn.as(browser, "google-sub-expired", owner.email());
        jdbc().sql("UPDATE identity.pending_google_links SET expires_at = now() - interval '1 second'").update();

        assertThat(login(browser, owner).status()).isEqualTo(200);

        assertThat(GoogleSignIn.as(anonymous(), "google-sub-expired", owner.email()).location()).isEqualTo(NEEDS_LINK);
    }

    @Test
    void savingALinkDeletesTheAccountsEarlierOnesAndDeletingTheAccountDeletesItsLinks() {
        TestUser owner = aUser().create();
        TestUser other = aUser().create();
        String first = links.save(owner.id(), "google-sub-first");
        links.save(other.id(), "google-sub-other");
        String second = links.save(owner.id(), "google-sub-second");

        assertThat(count()).as("one per account").isEqualTo(2);
        assertThat(links.claim(first, owner.id())).isEmpty();
        assertThat(links.claim(second, owner.id())).contains("google-sub-second");

        jdbc().sql("DELETE FROM identity.users WHERE id = :id").param("id", other.id()).update();

        assertThat(count()).as("the other account's link went with it").isEqualTo(1);
    }

    @Test
    void travelsInACookieThatTheClaimingLoginDeletes() {
        TestUser owner = aUser().email("cookie@example.test").create();
        ApiClient browser = anonymous();

        ApiResponse parked = GoogleSignIn.as(browser, "google-sub-cookie", owner.email());

        assertThat(parked.location()).isEqualTo(NEEDS_LINK);
        String header = parked.setCookie(PendingGoogleLinks.COOKIE);
        assertThat(header).as("the cookie's attributes")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Path=/api/auth;")
                .containsIgnoringCase("Max-Age=600;")
                .doesNotContainIgnoringCase("Secure");

        ApiResponse claimed = login(browser, owner);

        assertThat(claimed.status()).isEqualTo(200);
        String deletion = claimed.setCookie(PendingGoogleLinks.COOKIE);
        assertThat(Cookies.deletes(deletion)).as("deletes it: %s", deletion).isTrue();
        assertThat(HttpCookie.parse(deletion).getFirst().getPath()).as("under the path it was set on").isEqualTo("/api/auth");
    }

    static ApiResponse login(ApiClient browser, TestUser user) {
        return browser.post("/api/auth/login", Map.of("email", user.email(), "password", user.password()));
    }

    private long count() {
        return jdbc().sql("SELECT count(*) FROM identity.pending_google_links").query(Long.class).single();
    }
}

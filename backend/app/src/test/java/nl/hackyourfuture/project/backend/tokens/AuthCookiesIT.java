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

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way into an account sets the two token cookies, with the attributes that keep them out of
 * scripts and other sites and the lifetimes of the tokens they carry (Day 13).
 *
 * <p>Asserted on the {@code Set-Cookie} headers, because {@code ApiClient}'s jar keeps only name
 * and value: a cookie under the wrong path would still be sent back by it.
 */
@Import(GoogleSignIn.Config.class)
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class AuthCookiesIT extends IntegrationTest {

    @Test
    void loginSetsBothCookies() {
        TestUser user = aUser().create();

        assertBothCookies(anonymous().post("/api/auth/login", Map.of("email", user.email(), "password", user.password())));
    }

    @Test
    void aPasswordChangeSetsBothCookiesAnew() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);

        assertBothCookies(client.patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "Another-Password-1")));
    }

    @Test
    void googleSignInSetsBothCookies() {
        aUser().email("cookies@example.test").googleAccount("google-sub-cookies").create();

        assertBothCookies(GoogleSignIn.as(anonymous(), "google-sub-cookies", "cookies@example.test"));
    }

    @Test
    void deletingTheAccountDeletesBothCookies() {
        ApiResponse response = authenticatedAs(aUser().create()).delete("/api/users/me");

        assertThat(response.status()).isEqualTo(204);
        assertThat(Cookies.deletes(response.setCookie("access_token"))).as("deletes the access cookie").isTrue();
        assertThat(Cookies.deletes(response.setCookie("refresh_token"))).as("deletes the refresh cookie").isTrue();
    }

    private static void assertBothCookies(ApiResponse response) {
        assertThat(response.status()).isIn(200, 302);
        assertCookie(response, "access_token", "/", 900);
        assertCookie(response, "refresh_token", "/api/auth", 2_592_000);
    }

    private static void assertCookie(ApiResponse response, String name, String path, long maxAge) {
        String header = response.setCookie(name);
        List<HttpCookie> parsed = HttpCookie.parse(header);
        HttpCookie cookie = parsed.getFirst();

        assertThat(cookie.getValue()).as("%s's value", name).isNotBlank();
        assertThat(cookie.isHttpOnly()).as("%s is HttpOnly: %s", name, header).isTrue();
        assertThat(cookie.getPath()).as("%s's path: %s", name, header).isEqualTo(path);
        // Read off the header: HttpCookie works Max-Age out again from Expires when both are there.
        assertThat(header).as("%s's Max-Age", name).containsIgnoringCase("Max-Age=" + maxAge + ";");
        assertThat(header).as("%s is SameSite=Lax", name).containsIgnoringCase("SameSite=Lax");
    }
}

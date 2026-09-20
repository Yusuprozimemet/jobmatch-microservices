package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/auth/login} — the wire contract.
 *
 * <p>The session cookie is asserted by name and attributes, not by what is inside it. Phase 2
 * replaces the contents with a JWT; the assertions below are about {@code HttpOnly} and
 * {@code SameSite}, which have to keep holding either way.
 */
class AuthLoginIT extends IntegrationTest {

    private static final String SESSION_COOKIE = "JSESSIONID";

    @Test
    void logsInAndReturnsTheAccount() {
        TestUser user = aUser().name("Ada Lovelace").create();

        ApiResponse response = login(user.email(), user.password());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/email").asString()).isEqualTo(user.email());
        assertThat(response.at("/name").asString()).isEqualTo("Ada Lovelace");
        assertThat(response.at("/termsAcceptedAt").isNull()).isFalse();
    }

    @Test
    void setsASessionCookieThatJavascriptCannotReadAndOtherSitesCannotSend() {
        TestUser user = aUser().create();

        String cookie = login(user.email(), user.password()).setCookie(SESSION_COOKIE);

        assertThat(cookie).containsIgnoringCase("HttpOnly");
        assertThat(cookie).containsIgnoringCase("SameSite=Lax");
        assertThat(cookie).containsIgnoringCase("Path=/");
    }

    @Test
    void theSessionCookieReachesAnAuthenticatedEndpoint() {
        TestUser user = aUser().create();
        ApiClient client = anonymous();

        client.post("/api/auth/login", Map.of("email", user.email(), "password", user.password()));

        assertThat(client.cookies()).containsKey(SESSION_COOKIE);
        assertThat(client.get("/api/users/me").status()).isEqualTo(200);
    }

    // Null means the user never agreed and the frontend shows the terms screen first.
    @Test
    void reportsANullTermsTimestampForAnAccountThatNeverAgreed() {
        TestUser user = aUser().withoutAcceptedTerms().create();

        ApiResponse response = login(user.email(), user.password());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/termsAcceptedAt").isNull()).isTrue();
    }

    @Test
    void rejectsTheWrongPassword() {
        TestUser user = aUser().create();

        ApiResponse response = login(user.email(), "not-the-password");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.at("/detail").asString()).isEqualTo("Invalid email or password");
    }

    // A wrong address and a wrong password answer identically, so the response cannot be
    // used to find out which addresses are registered.
    @Test
    void rejectsAnUnknownEmailWithTheSameAnswerAsAWrongPassword() {
        TestUser user = aUser().create();

        ApiResponse unknown = login("nobody@example.test", "Password123!");
        ApiResponse wrongPassword = login(user.email(), "not-the-password");

        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.at("/detail").asString()).isEqualTo(wrongPassword.at("/detail").asString());
    }

    // Same reason: a Google-only account has no password, and saying so would leak how an
    // address signs in.
    @Test
    void rejectsAGoogleOnlyAccountWithTheSameAnswer() {
        TestUser google = aUser().email("google-only@example.test").googleAccount("google-sub-1").create();

        ApiResponse response = login(google.email(), "Password123!");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.at("/detail").asString()).isEqualTo("Invalid email or password");
    }

    @Test
    void failedLoginLeavesTheCallerAnonymous() {
        TestUser user = aUser().create();
        ApiClient client = anonymous();

        client.post("/api/auth/login", Map.of("email", user.email(), "password", "not-the-password"));

        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    @Test
    void acceptsTheRegisteredAddressInAnyCase() {
        TestUser user = aUser().email("case@example.test").create();

        ApiResponse response = login("CASE@Example.Test", user.password());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/email").asString()).isEqualTo("case@example.test");
    }

    @Test
    void rejectsARequestWithNoPassword() {
        TestUser user = aUser().create();

        ApiResponse response = anonymous().post("/api/auth/login", Map.of("email", user.email(), "password", ""));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/password").isMissingNode()).isFalse();
    }

    private ApiResponse login(String email, String password) {
        return anonymous().post("/api/auth/login", Map.of("email", email, "password", password));
    }
}

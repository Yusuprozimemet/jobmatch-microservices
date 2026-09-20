package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/auth/logout} — the wire contract.
 *
 * <p>There is no controller behind this route today; it is the filter chain's logout handler.
 * That is exactly the kind of detail these tests must not depend on, so everything below is
 * about what the caller receives.
 */
class AuthLogoutIT extends IntegrationTest {

    private static final String SESSION_COOKIE = "JSESSIONID";

    @Test
    void returnsJsonRatherThanARedirect() {
        ApiClient client = authenticatedAs(aUser().create());

        ApiResponse response = client.post("/api/auth/logout", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/message").asString()).isEqualTo("Logged out successfully");
    }

    @Test
    void clearsTheSessionCookie() {
        ApiClient client = authenticatedAs(aUser().create());

        ApiResponse response = client.post("/api/auth/logout", null);

        // An empty value with an expiry in the past is how a cookie is deleted.
        assertThat(response.setCookie(SESSION_COOKIE)).startsWith("JSESSIONID=;");
        assertThat(response.setCookie(SESSION_COOKIE)).contains("Expires=Thu, 01 Jan 1970");
        assertThat(client.cookies()).doesNotContainKey(SESSION_COOKIE);
    }

    @Test
    void leavesTheCallerUnauthenticated() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        assertThat(client.get("/api/users/me").status()).isEqualTo(200);

        client.post("/api/auth/logout", null);

        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    // Logging out twice, or without a session at all, is not an error - the frontend calls it
    // whenever it wants to be sure the caller is signed out.
    @Test
    void succeedsWithoutASession() {
        ApiResponse response = anonymous().post("/api/auth/logout", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/message").asString()).isEqualTo("Logged out successfully");
    }
}

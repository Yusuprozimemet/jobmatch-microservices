package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
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

    @Test
    void returnsJsonRatherThanARedirect() {
        ApiClient client = authenticatedAs(aUser().create());

        ApiResponse response = client.post("/api/auth/logout", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/message").asString()).isEqualTo("Logged out successfully");
    }

    @Test
    void clearsTheAuthCookie() {
        ApiClient client = authenticatedAs(aUser().create());

        ApiResponse response = client.post("/api/auth/logout", null);

        // How the deletion is spelled is the server's business; that it deletes is the contract.
        assertThat(Cookies.deletes(response.setCookie(Cookies.AUTH)))
                .as("logout deletes the %s cookie", Cookies.AUTH)
                .isTrue();
        assertThat(client.cookies()).doesNotContainKey(Cookies.AUTH);
    }

    @Test
    void leavesTheCallerUnauthenticated() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        assertThat(client.get("/api/users/me").status()).isEqualTo(200);

        client.post("/api/auth/logout", null);

        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    // Logging out twice, or without being signed in at all, is not an error - the frontend
    // calls it whenever it wants to be sure the caller is signed out.
    @Test
    void succeedsWithoutBeingSignedIn() {
        ApiResponse response = anonymous().post("/api/auth/logout", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/message").asString()).isEqualTo("Logged out successfully");
    }
}

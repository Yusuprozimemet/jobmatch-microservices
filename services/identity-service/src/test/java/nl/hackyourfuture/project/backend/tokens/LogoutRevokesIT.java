package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.token.RefreshTokens;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Logging out spends the refresh token as well as deleting it from the browser (Day 13). */
class LogoutRevokesIT extends IntegrationTest {

    @Autowired
    private RefreshTokens refreshTokens;

    @Test
    void logoutRevokesTheRefreshTokenAndDeletesBothCookies() {
        ApiClient client = authenticatedAs(aUser().create());
        String refresh = client.cookies().get("refresh_token");

        ApiResponse response = client.post("/api/auth/logout", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(refreshTokens.redeem(refresh)).as("the refresh token after logout").isEmpty();
        assertThat(Cookies.deletes(response.setCookie("access_token"))).as("deletes the access cookie").isTrue();
        assertThat(Cookies.deletes(response.setCookie("refresh_token"))).as("deletes the refresh cookie").isTrue();
    }
}

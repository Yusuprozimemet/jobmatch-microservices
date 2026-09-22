package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PATCH /api/auth/password} — the one route under {@code /api/auth/**} that needs a
 * session, and the one whose access rule is easiest to lose when the filter chain is rewritten
 * behind a gateway.
 */
class AuthPasswordUpdateIT extends IntegrationTest {

    @Test
    void refusesAnUnauthenticatedCaller() {
        ApiResponse response = anonymous().patch("/api/auth/password",
                Map.of("currentPassword", "Password123!", "newPassword", "BrandNewPassword1!"));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void changesThePassword() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "BrandNewPassword1!"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(login(user.email(), "BrandNewPassword1!").status()).isEqualTo(200);
        assertThat(login(user.email(), user.password()).status()).isEqualTo(401);
    }

    // The credential is re-issued as part of the change, so the caller stays signed in and a
    // cookie captured before the change is no longer the one in play. Day 13 has to keep that
    // true for the JWT cookie, which is why its spec now says so in scope.
    @Test
    void leavesTheCallerSignedIn() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        String cookieBefore = client.cookies().get(Cookies.AUTH);

        client.patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "BrandNewPassword1!"));

        assertThat(client.cookies().get(Cookies.AUTH)).isNotEqualTo(cookieBefore);
        assertThat(client.get("/api/users/me").status()).isEqualTo(200);
    }

    @Test
    void refusesTheWrongCurrentPassword() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).patch("/api/auth/password",
                Map.of("currentPassword", "not-the-password", "newPassword", "BrandNewPassword1!"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).isEqualTo("Current password is incorrect");
        assertThat(login(user.email(), user.password()).status()).isEqualTo(200);
    }

    @Test
    void rejectsANewPasswordShorterThanSixCharacters() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "12345"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/newPassword").asString()).isEqualTo("Password must be at least 6 characters");
    }

    @Test
    void rejectsARequestWithNoCurrentPassword() {
        TestUser user = aUser().create();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newPassword", "BrandNewPassword1!");

        ApiResponse response = authenticatedAs(user).patch("/api/auth/password", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/currentPassword").asString()).isEqualTo("Current password is required");
    }

    private ApiResponse login(String email, String password) {
        return anonymous().post("/api/auth/login", Map.of("email", email, "password", password));
    }
}

package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.token.RefreshTokens;
import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.HttpCookie;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new password leaves no refresh token from before it working, wherever it was issued: the
 * caller of a change gets a new one, and a reset signs every device out (Day 13).
 */
class PasswordRevokesIT extends IntegrationTest {

    private static final String REFRESH = "refresh_token";

    @Autowired
    private RefreshTokens refreshTokens;

    @Test
    void aPasswordChangeRevokesEveryRefreshTokenAndIssuesTheCallerANewOne() {
        TestUser user = aUser().create();
        ApiClient caller = authenticatedAs(user);
        String callersOld = caller.cookies().get(REFRESH);
        String otherDevice = authenticatedAs(user).cookies().get(REFRESH);

        ApiResponse response = caller.patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "Another-Password-1"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(refreshTokens.redeem(callersOld)).as("the caller's old refresh token").isEmpty();
        assertThat(refreshTokens.redeem(otherDevice)).as("another device's refresh token").isEmpty();
        String renewed = HttpCookie.parse(response.setCookie(REFRESH)).getFirst().getValue();
        assertThat(refreshTokens.redeem(renewed)).map(User::getId).contains(user.id());
    }

    @Test
    void aPasswordResetRevokesEveryRefreshToken() {
        TestUser user = aUser().create();
        String refresh = authenticatedAs(user).cookies().get(REFRESH);
        String resetToken = UUID.randomUUID().toString();
        jdbc().sql("INSERT INTO identity.password_reset_tokens (id, user_id, token, expiry_date) "
                        + "VALUES (:id, :userId, :token, now() + interval '1 hour')")
                .param("id", UUID.randomUUID())
                .param("userId", user.id())
                .param("token", resetToken)
                .update();

        ApiResponse response = anonymous().post("/api/auth/reset-password",
                Map.of("token", resetToken, "newPassword", "Another-Password-1"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(refreshTokens.redeem(refresh)).as("the refresh token from before the reset").isEmpty();
    }
}

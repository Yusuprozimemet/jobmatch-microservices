package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No route outside Google sign-in sets a session cookie, not even to delete one (Day 13). Google
 * sign-in's steps are in {@code GoogleNoSessionIT} (Day 14).
 */
class NoSessionIT extends IntegrationTest {

    @Test
    void noResponseInAnAccountsLifeSetsJsessionid() {
        TestUser user = aUser().create();
        ApiClient client = anonymous();
        List<ApiResponse> responses = new ArrayList<>();

        responses.add(client.post("/api/auth/register", Map.of("name", "No Session", "email", "no-session@example.test",
                "password", "Password123!", "acceptedTerms", true)));
        responses.add(client.post("/api/auth/login", Map.of("email", user.email(), "password", user.password())));
        responses.add(client.get("/api/users/me"));
        responses.add(client.patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "Another-Password-1")));
        responses.add(client.post("/api/auth/refresh", null));
        responses.add(client.post("/api/auth/logout", null));
        responses.add(client.post("/api/auth/login", Map.of("email", user.email(), "password", "Another-Password-1")));
        responses.add(client.delete("/api/users/me"));

        assertThat(responses).extracting(ApiResponse::status).containsExactly(201, 200, 200, 200, 200, 200, 200, 204);
        assertThat(responses).flatExtracting(ApiResponse::setCookieHeaders)
                .noneMatch(header -> header.startsWith("JSESSIONID="));
    }
}

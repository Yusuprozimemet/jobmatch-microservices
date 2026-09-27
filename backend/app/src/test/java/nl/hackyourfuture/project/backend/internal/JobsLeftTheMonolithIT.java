package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monolith no longer serves job search or the postings routes (Day 17, Track D). job-service does.
 * Uses {@code direct()} throughout, so it always talks to the monolith itself, gateway run or not.
 */
class JobsLeftTheMonolithIT extends IntegrationTest {

    @Autowired
    private ServiceToken serviceToken;

    @ParameterizedTest
    @ValueSource(strings = {"/api/jobs", "/api/jobs/filters", "/api/jobs/seed-0001"})
    void anonymousJobsPathsAnswer401(String path) {
        ApiClient client = direct();

        ApiResponse response = client.get(path);

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void loggedInUserJobsPathsAnswer404() {
        TestUser user = aUser().create();
        ApiClient client = direct();
        ApiResponse loginResponse = client.post("/api/auth/login",
                Map.of("email", user.email(), "password", user.password()));
        assertThat(loginResponse.status()).isEqualTo(200);

        for (String path : new String[]{"/api/jobs", "/api/jobs/filters", "/api/jobs/seed-0001"}) {
            ApiResponse response = client.get(path);
            assertThat(response.status())
                    .as("GET " + path + " for logged-in user")
                    .isEqualTo(404);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/postings/batch", "/internal/postings/shortlist"})
    void monolithServiceTokenPostingsPathsAnswer404(String path) {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        ApiResponse response = client.post(path, Map.of());

        assertThat(response.status()).isEqualTo(404);
    }
}

package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A logged-in caller whose user no longer exists: the session outlives the {@code users} row.
 *
 * <p>Each module answers this in its own way, and today those answers differ. {@code applications}
 * and {@code identity} say 404 "User not found"; {@code matching} says 422 "Fill in your profile",
 * treating no account like no profile. Day 10 moves the email-to-user lookup out of those modules
 * into one resolver, which is exactly where the answers could be flattened into one. Changing
 * them is a behaviour change nobody has asked for, so this pins them first.
 *
 * <p>The case is real: an account deleted in one tab leaves a live session in another. Day 13
 * replaces the session with a token, and a token for a deleted user must get the same answers.
 */
class SessionWithoutAUserIT extends IntegrationTest {

    private ApiClient client;

    @BeforeEach
    void aSessionWhoseUserIsGone() {
        TestUser user = aUser().create();
        client = authenticatedAs(user);
        jdbc().sql("DELETE FROM users WHERE id = :id").param("id", user.id()).update();
    }

    @Test
    void savedJobsSayTheUserIsNotFound() {
        assertUserNotFound(client.get("/api/saved-jobs"));
        assertUserNotFound(client.get("/api/saved-jobs/stats"));
        assertUserNotFound(client.post("/api/saved-jobs", Map.of("postingId", "seed-0001")));
    }

    // No account and no profile are the same answer here: there is nothing to rank against.
    @Test
    void topMatchesAsksForAProfile() {
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.at("/detail").asString()).contains("Fill in your profile");
    }

    @Test
    void identitySaysTheUserIsNotFound() {
        assertUserNotFound(client.get("/api/users/me"));
        assertUserNotFound(client.get("/api/profile"));
    }

    private static void assertUserNotFound(ApiResponse response) {
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.at("/detail").asString()).isEqualTo("User not found");
    }
}

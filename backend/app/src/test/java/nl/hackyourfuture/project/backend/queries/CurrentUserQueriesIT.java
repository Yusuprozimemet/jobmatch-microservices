package nl.hackyourfuture.project.backend.queries;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.StatementCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many statements it takes to find out who is asking: one per request.
 *
 * <p>Not a contract test. Today the principal is the access token's email, and {@code identity}
 * turns it into a user with one query. Before Day 10 that query runs inside {@code applications}
 * and {@code matching}, through an interface in {@code shared}. After it, it runs once in a
 * resolver at the edge. The move must not add a second lookup on the way, and that is what this
 * test is for.
 *
 * <p>It does not expire. Day 13 put the id in the token's {@code sub} but kept the email as the
 * principal, and this one lookup is the deleted-user check: it is what refuses a token that
 * outlived its user ({@code SessionWithoutAUserIT}). A service that trusts {@code sub} asks
 * {@code GET /internal/users/{id}} instead (Day 39).
 */
class CurrentUserQueriesIT extends MatchingTest {

    private ApiClient client;

    @BeforeEach
    void aUserWithAProfile() {
        client = authenticatedAs(userWithProfile());
    }

    @Test
    void savingAJobLooksTheUserUpOnce() {
        StatementCounter.reset();

        ApiResponse response = client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(StatementCounter.statementsMentioning("users")).isEqualTo(1);
    }

    @Test
    void topMatchesLooksTheUserUpOnce() {
        StatementCounter.reset();

        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(StatementCounter.statementsMentioning("users")).isEqualTo(1);
    }
}

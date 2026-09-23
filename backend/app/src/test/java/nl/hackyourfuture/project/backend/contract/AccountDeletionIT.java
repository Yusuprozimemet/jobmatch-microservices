package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DELETE /api/users/me}: the account goes, and so does everything saved under it.
 *
 * <p>Nothing in Days 01–04 called this route. Today the saved jobs go by a foreign key,
 * {@code fk_saved_jobs_user ... ON DELETE CASCADE}, from {@code saved_jobs} to {@code users}.
 * Day 11 moves those two tables into different schemas owned by different roles, and Day 27
 * replaces the key with a {@code user.deleted} event. Either change can leave a deleted user's
 * rows behind with every other test still green, which is what this is for.
 *
 * <p>The rows are counted in the table, not through the API: once the account is gone no route
 * can show what is left of it.
 */
class AccountDeletionIT extends IntegrationTest {

    @Test
    void deletingTheAccountRemovesItsSavedJobs() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0002"));
        assertThat(savedJobsOf(user.id())).isEqualTo(2);

        assertThat(client.delete("/api/users/me").status()).isEqualTo(204);

        assertThat(savedJobsOf(user.id())).isZero();
    }

    @Test
    void leavesEveryoneElsesSavedJobsAlone() {
        TestUser stranger = aUser().create();
        ApiClient strangerClient = authenticatedAs(stranger);
        strangerClient.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));
        TestUser user = aUser().create();
        authenticatedAs(user).post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        authenticatedAs(user).delete("/api/users/me");

        assertThat(savedJobsOf(stranger.id())).isEqualTo(1);
        assertThat(strangerClient.get("/api/saved-jobs").at("/totalElements").asLong()).isEqualTo(1);
    }

    // The session goes with the account, so the next call is "not logged in", not "no such user".
    @Test
    void endsTheSession() {
        ApiClient client = authenticatedAs(aUser().create());

        client.delete("/api/users/me");

        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    private long savedJobsOf(UUID userId) {
        return jdbc().sql("SELECT count(*) FROM saved_jobs WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}

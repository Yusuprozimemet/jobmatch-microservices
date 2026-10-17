package nl.hackyourfuture.project.backend.events;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deleted account's saves leave the public {@code savedCount}, on job search and job detail,
 * within 10 s of {@code DELETE /api/users/me} answering 204.
 *
 * <p>Until Day 25 the saves went by {@code ON DELETE CASCADE} in the delete's own transaction, so
 * the count dropped before the 204. Since then they go when application-service handles the
 * {@code user.deleted} event, and anyone calling job search in between still sees them counted.
 * {@code AccountDeletionIT} waits for the rows in apps_db; nothing waited for the number the API
 * shows, which is the one a caller can see (docs/paper, §4.1). The bound is the same 10 s; on
 * compose at the end point it took 0.3 to 7.3 s. The user who stays keeps their save counted.
 */
class SavedCountAfterDeletionIT extends IntegrationTest {

    private static final String POSTING = "seed-0001";
    private static final long WAIT_MILLIS = 10_000;

    @Test
    void aDeletedAccountsSaveLeavesTheCountWithinTenSeconds() throws InterruptedException {
        TestUser stays = aUser().create();
        TestUser leaves = aUser().create();
        authenticatedAs(stays).post("/api/saved-jobs", Map.of("postingId", POSTING));
        authenticatedAs(leaves).post("/api/saved-jobs", Map.of("postingId", POSTING));
        assertThat(savedCountInSearch()).isEqualTo(2);
        assertThat(savedCountInDetail()).isEqualTo(2);

        assertThat(authenticatedAs(leaves).delete("/api/users/me").status()).isEqualTo(204);

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while ((savedCountInSearch() != 1 || savedCountInDetail() != 1)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(savedCountInSearch()).isEqualTo(1);
        assertThat(savedCountInDetail()).isEqualTo(1);
    }

    private int savedCountInDetail() {
        return anonymous().get("/api/jobs/" + POSTING).at("/savedCount").asInt();
    }

    private int savedCountInSearch() {
        ApiResponse response = anonymous().get("/api/jobs?size=100");
        for (JsonNode posting : response.at("/content")) {
            if (POSTING.equals(posting.get("postingId").asString())) {
                return posting.get("savedCount").asInt();
            }
        }
        throw new AssertionError("Posting " + POSTING + " was not in the search results");
    }
}

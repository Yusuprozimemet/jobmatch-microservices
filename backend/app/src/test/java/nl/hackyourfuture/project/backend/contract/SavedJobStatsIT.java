package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/saved-jobs/stats} — the application tracker's counts by state.
 *
 * <p>The dashboard renders these directly, so the shape matters as much as the numbers: the
 * response is a map keyed by state, and a state nobody is in is <em>absent</em> rather than
 * zero. Phase 5 recomputes this from application-service's own table; the contract below is
 * what it has to keep producing.
 */
class SavedJobStatsIT extends IntegrationTest {

    @Test
    void countsTheJobsInEachState() {
        ApiClient client = authenticatedAs(aUser().create());
        saveIn(client, "seed-0001", "APPLIED");
        saveIn(client, "seed-0002", "APPLIED");
        saveIn(client, "seed-0003", "REJECTED");
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0004"));

        ApiResponse stats = client.get("/api/saved-jobs/stats");

        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.at("/APPLIED").asInt()).isEqualTo(2);
        assertThat(stats.at("/REJECTED").asInt()).isEqualTo(1);
        assertThat(stats.at("/SAVED").asInt()).isEqualTo(1);
    }

    @Test
    void theCountsSumToTheNumberOfSavedJobs() {
        ApiClient client = authenticatedAs(aUser().create());
        saveIn(client, "seed-0001", "APPLIED");
        saveIn(client, "seed-0002", "ACCEPTED");
        saveIn(client, "seed-0003", "DECLINED");
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0004"));

        ApiResponse stats = client.get("/api/saved-jobs/stats");
        long savedJobs = client.get("/api/saved-jobs").at("/totalElements").asLong();

        int total = 0;
        for (var count : stats.json().properties()) {
            total += count.getValue().asInt();
        }
        assertThat(total).isEqualTo((int) savedJobs).isEqualTo(4);
    }

    // Absent, not zero. The dashboard treats a missing key as nothing to show, and a
    // reimplementation that helpfully filled in zeroes would change what it renders.
    @Test
    void leavesOutAStateNothingIsIn() {
        ApiClient client = authenticatedAs(aUser().create());
        saveIn(client, "seed-0001", "APPLIED");

        ApiResponse stats = client.get("/api/saved-jobs/stats");

        assertThat(stats.at("/APPLIED").asInt()).isEqualTo(1);
        assertThat(stats.at("/SAVED").isMissingNode()).isTrue();
        assertThat(stats.at("/REJECTED").isMissingNode()).isTrue();
        assertThat(stats.at("/ACCEPTED").isMissingNode()).isTrue();
        assertThat(stats.at("/DECLINED").isMissingNode()).isTrue();
    }

    @Test
    void isEmptyForAUserWhoHasSavedNothing() {
        ApiResponse stats = authenticatedAs(aUser().create()).get("/api/saved-jobs/stats");

        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.json().isEmpty()).isTrue();
    }

    @Test
    void followsTheJobAsItMovesState() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        client.patch("/api/saved-jobs/seed-0001", Map.of("newState", "APPLIED"));

        ApiResponse stats = client.get("/api/saved-jobs/stats");
        assertThat(stats.at("/APPLIED").asInt()).isEqualTo(1);
        assertThat(stats.at("/SAVED").isMissingNode()).isTrue();
    }

    @Test
    void countsOnlyTheCallersOwnJobs() {
        TestUser owner = aUser().create();
        ApiClient ownerClient = authenticatedAs(owner);
        saveIn(ownerClient, "seed-0001", "APPLIED");
        saveIn(ownerClient, "seed-0002", "APPLIED");

        ApiResponse stats = authenticatedAs(aUser().create()).get("/api/saved-jobs/stats");

        assertThat(stats.json().isEmpty()).isTrue();
    }

    private static void saveIn(ApiClient client, String postingId, String state) {
        client.post("/api/saved-jobs", Map.of("postingId", postingId));
        client.patch("/api/saved-jobs/" + postingId, Map.of("newState", state));
    }
}

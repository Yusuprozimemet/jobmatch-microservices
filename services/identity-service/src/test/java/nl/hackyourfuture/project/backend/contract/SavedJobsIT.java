package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/saved-jobs} — saving, listing, moving between states and removing.
 *
 * <p>The saved job is the one row the user owns outright: the mart is republished under it and
 * the posting can disappear, but the save and its state are theirs. Phase 5 moves this table
 * into application-service and puts a network between it and the postings, so what is asserted
 * here is the user-visible behaviour only, never which table answered.
 */
class SavedJobsIT extends IntegrationTest {

    @Test
    void savesAPosting() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).post("/api/saved-jobs", save("seed-0001"));

        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void aNewlySavedJobStartsInTheSavedState() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        ApiResponse list = client.get("/api/saved-jobs");

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.at("/content/0/postingId").asString()).isEqualTo("seed-0001");
        assertThat(list.at("/content/0/jobState").asString()).isEqualTo("SAVED");
    }

    @Test
    void savingTheSamePostingTwiceIsAConflict() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        ApiResponse second = client.post("/api/saved-jobs", save("seed-0001"));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.at("/detail").asString()).isEqualTo("Job is already saved");
    }

    // The conflict is per user, not per posting: a popular job is saved by many people.
    @Test
    void twoUsersMaySaveTheSamePosting() {
        TestUser first = aUser().create();
        TestUser second = aUser().create();

        assertThat(authenticatedAs(first).post("/api/saved-jobs", save("seed-0001")).status())
                .isEqualTo(201);
        assertThat(authenticatedAs(second).post("/api/saved-jobs", save("seed-0001")).status())
                .isEqualTo(201);
    }

    // Nothing checks the posting against the mart. A save is accepted for an id that is not
    // there, which is what makes the missing-posting case in SavedJobHydrationIT reachable.
    @Test
    void savesAPostingIdThatIsNotInTheMart() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).post("/api/saved-jobs", save("not-a-posting"));

        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void movesASavedJobToAnotherState() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        ApiResponse patch = client.patch("/api/saved-jobs/seed-0001", Map.of("newState", "APPLIED"));

        assertThat(patch.status()).isEqualTo(200);
        assertThat(client.get("/api/saved-jobs").at("/content/0/jobState").asString())
                .isEqualTo("APPLIED");
    }

    // Every state the tracker offers is reachable, in any order - there is no workflow to
    // walk, and a user who was rejected may still move the row to DECLINED.
    @Test
    void movesThroughEveryState() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        for (String state : List.of("APPLIED", "REJECTED", "ACCEPTED", "DECLINED", "SAVED")) {
            assertThat(client.patch("/api/saved-jobs/seed-0001", Map.of("newState", state)).status())
                    .as("moving to %s", state)
                    .isEqualTo(200);
            assertThat(client.get("/api/saved-jobs").at("/content/0/jobState").asString())
                    .isEqualTo(state);
        }
    }

    @Test
    void movingAJobThatWasNeverSavedIsANotFound() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user)
                .patch("/api/saved-jobs/seed-0001", Map.of("newState", "APPLIED"));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void removesASavedJob() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        ApiResponse removed = client.delete("/api/saved-jobs/seed-0001");

        assertThat(removed.status()).isEqualTo(204);
        assertThat(postingIds(client.get("/api/saved-jobs"))).isEmpty();
    }

    @Test
    void removingAJobThatWasNeverSavedIsANotFound() {
        TestUser user = aUser().create();

        assertThat(authenticatedAs(user).delete("/api/saved-jobs/seed-0001").status()).isEqualTo(404);
    }

    // Removing is not a state: the row is gone and the posting can be saved again from scratch.
    @Test
    void aRemovedJobCanBeSavedAgain() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));
        client.patch("/api/saved-jobs/seed-0001", Map.of("newState", "REJECTED"));
        client.delete("/api/saved-jobs/seed-0001");

        assertThat(client.post("/api/saved-jobs", save("seed-0001")).status()).isEqualTo(201);
        assertThat(client.get("/api/saved-jobs").at("/content/0/jobState").asString())
                .isEqualTo("SAVED");
    }

    @Test
    void oneUsersSavedJobsAreInvisibleToAnother() {
        TestUser owner = aUser().create();
        TestUser stranger = aUser().create();
        authenticatedAs(owner).post("/api/saved-jobs", save("seed-0001"));

        ApiClient strangerClient = authenticatedAs(stranger);

        assertThat(postingIds(strangerClient.get("/api/saved-jobs"))).isEmpty();
        // And a stranger cannot reach into someone else's row by id.
        assertThat(strangerClient.delete("/api/saved-jobs/seed-0001").status()).isEqualTo(404);
        assertThat(strangerClient.patch("/api/saved-jobs/seed-0001", Map.of("newState", "APPLIED"))
                .status()).isEqualTo(404);
    }

    @Test
    void pagesTheSavedJobs() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        for (int i = 1; i <= 3; i++) {
            client.post("/api/saved-jobs", save("seed-000" + i));
        }

        ApiResponse firstPage = client.get("/api/saved-jobs?page=0&size=2");
        ApiResponse secondPage = client.get("/api/saved-jobs?page=1&size=2");

        assertThat(firstPage.at("/totalElements").asLong()).isEqualTo(3);
        assertThat(postingIds(firstPage)).hasSize(2);
        assertThat(postingIds(secondPage)).hasSize(1)
                .doesNotContainAnyElementsOf(postingIds(firstPage));
    }

    @Test
    void rejectsASaveWithNoPostingId() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).post("/api/saved-jobs", Map.of("postingId", " "));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/postingId").asString()).isEqualTo("Posting ID is required");
    }

    @Test
    void rejectsAStateThatIsNotOneOfTheFive() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", save("seed-0001"));

        assertThat(client.patch("/api/saved-jobs/seed-0001", Map.of("newState", "GHOSTED")).status())
                .isEqualTo(400);
    }

    @Test
    void everyRouteRequiresLogin() {
        ApiClient visitor = anonymous();

        assertThat(visitor.get("/api/saved-jobs").status()).isEqualTo(401);
        assertThat(visitor.post("/api/saved-jobs", save("seed-0001")).status()).isEqualTo(401);
        assertThat(visitor.patch("/api/saved-jobs/seed-0001", Map.of("newState", "APPLIED")).status())
                .isEqualTo(401);
        assertThat(visitor.delete("/api/saved-jobs/seed-0001").status()).isEqualTo(401);
        assertThat(visitor.get("/api/saved-jobs/stats").status()).isEqualTo(401);
    }

    private static Map<String, Object> save(String postingId) {
        return Map.of("postingId", postingId);
    }

    private static List<String> postingIds(ApiResponse response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode savedJob : response.at("/content")) {
            ids.add(savedJob.get("postingId").asString());
        }
        return ids;
    }
}

package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code savedCount} on job search and job detail — the number of users who saved a posting.
 *
 * <p>This is the assertion Day 03 exists for. Today the number comes from a correlated
 * subquery reaching out of the mart and into {@code saved_jobs}; Day 08 deletes that join and
 * asks application-service for the counts instead, and Day 25 puts a network between them.
 * None of that is visible here, so <strong>this file must not need editing</strong>. If it
 * does, the replacement interface has the wrong shape.
 */
class JobSavedCountIT extends IntegrationTest {

    @Test
    void reportsHowManyUsersSavedAPosting() {
        savePosting("seed-0001", aUser().create(), aUser().create(), aUser().create());

        assertThat(savedCountInSearch("seed-0001")).isEqualTo(3);
    }

    @Test
    void reportsTheSameCountOnTheDetailEndpoint() {
        savePosting("seed-0001", aUser().create(), aUser().create(), aUser().create());

        ApiResponse detail = anonymous().get("/api/jobs/seed-0001");

        assertThat(detail.at("/savedCount").asInt()).isEqualTo(3);
    }

    // Zero, not null: the frontend renders the number without a check.
    @Test
    void reportsZeroForAPostingNobodySaved() {
        ApiResponse detail = anonymous().get("/api/jobs/seed-0002");

        assertThat(detail.at("/savedCount").isNull()).isFalse();
        assertThat(detail.at("/savedCount").asInt()).isZero();
        assertThat(savedCountInSearch("seed-0002")).isZero();
    }

    @Test
    void countsOnlyTheUsersWhoSavedThatPosting() {
        TestUser user = aUser().create();
        savePosting("seed-0003", user);

        assertThat(savedCountInSearch("seed-0003")).isEqualTo(1);
        assertThat(savedCountInSearch("seed-0004")).isZero();
    }

    // The count is a property of the posting, not of the caller: a logged-out visitor sees
    // the same number as the user who saved it. Easy to get wrong when the join is replaced
    // by a per-user call, which is why it is pinned before the replacement.
    @Test
    void reportsTheSameCountToEveryCaller() {
        TestUser saver = aUser().create();
        TestUser bystander = aUser().create();
        savePosting("seed-0005", saver);

        assertThat(savedCountInSearch("seed-0005")).isEqualTo(1);
        assertThat(authenticatedAs(saver).get("/api/jobs/seed-0005").at("/savedCount").asInt())
                .isEqualTo(1);
        assertThat(authenticatedAs(bystander).get("/api/jobs/seed-0005").at("/savedCount").asInt())
                .isEqualTo(1);
    }

    @Test
    void stopsCountingAUserWhoUnsaved() {
        TestUser stays = aUser().create();
        TestUser leaves = aUser().create();
        savePosting("seed-0006", stays, leaves);

        unsavePosting("seed-0006", leaves);

        assertThat(savedCountInSearch("seed-0006")).isEqualTo(1);
    }

    // Written straight to the table rather than through POST /api/saved-jobs: that endpoint
    // is Day 04's subject, and this file should not fail when Day 04 changes it.
    private void savePosting(String postingId, TestUser... users) {
        for (TestUser user : users) {
            jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                    .param("userId", user.id())
                    .param("postingId", postingId)
                    .update();
        }
    }

    private void unsavePosting(String postingId, TestUser user) {
        jdbc().sql("DELETE FROM saved_jobs WHERE user_id = :userId AND posting_id = :postingId")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }

    private int savedCountInSearch(String postingId) {
        ApiResponse response = anonymous().get("/api/jobs?size=100");
        for (JsonNode posting : response.at("/content")) {
            if (postingId.equals(posting.get("postingId").asString())) {
                return posting.get("savedCount").asInt();
            }
        }
        throw new AssertionError("Posting " + postingId + " was not in the search results");
    }
}

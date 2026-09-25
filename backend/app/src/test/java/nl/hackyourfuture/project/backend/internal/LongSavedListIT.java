package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saved jobs fetches a user's whole list in one {@code PostingLookup.byIds} call. From Day 19 that
 * call goes over HTTP, to a route that refuses more than 500 distinct ids (Day 18), so the client
 * has to split a longer list. Green before the client exists, which is what it holds.
 */
class LongSavedListIT extends IntegrationTest {

    private static final int SAVED = 501;

    @Test
    void aListLongerThanTheBatchCapStillCarriesEveryPostingsDetails() {
        TestUser user = aUser().create();
        savePostings(user);

        ApiClient client = authenticatedAs(user);
        Map<String, String> titles = new HashMap<>();
        for (int page = 0; page < 6; page++) {
            ApiResponse response = client.get("/api/saved-jobs?page=" + page + "&size=100");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/totalElements").asInt()).isEqualTo(SAVED);
            for (JsonNode job : response.at("/content")) {
                titles.put(job.at("/postingId").asString(), job.at("/title").asString());
            }
        }

        assertThat(titles).hasSize(SAVED);
        titles.forEach((id, title) -> assertThat(title).isEqualTo("Long " + id.substring("long-".length())));
    }

    /**
     * One posting from the builder, copied {@value #SAVED} times in one statement with its id and
     * title changed, then saved in another: {@value #SAVED} builder calls took most of a minute.
     * Only {@code fct_postings} is copied, which is all saved jobs reads.
     */
    private void savePostings(TestUser user) {
        aPosting().id("long-template").title("Long template").create();
        jdbc().sql("""
                        INSERT INTO analytics.fct_postings
                        SELECT (jsonb_populate_record(p, jsonb_build_object(
                                   'posting_id', 'long-' || n,
                                   'source_job_id', 'test-source-long-' || n,
                                   'title', 'Long ' || n))).*
                        FROM analytics.fct_postings p, generate_series(0, :last) n
                        WHERE p.posting_id = 'long-template'
                        """)
                .param("last", SAVED - 1)
                .update();
        jdbc().sql("""
                        INSERT INTO saved_jobs (user_id, posting_id)
                        SELECT :userId, 'long-' || n FROM generate_series(0, :last) n
                        """)
                .param("userId", user.id())
                .param("last", SAVED - 1)
                .update();
    }
}

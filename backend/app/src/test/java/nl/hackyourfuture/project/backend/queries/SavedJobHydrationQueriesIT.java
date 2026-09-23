package nl.hackyourfuture.project.backend.queries;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StatementCounter;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many statements it takes to put posting details on a page of saved jobs: one, whatever
 * the page size and however long the list.
 *
 * <p>Not a contract test. {@code SavedJobHydrationIT} pins what the user sees; this pins how
 * often the mart is asked. Before Day 09 the one statement is the saved-jobs query itself,
 * which carries a {@code LEFT JOIN} into {@code analytics.fct_postings}. After it, the one
 * statement is the batched lookup {@code jobs} runs. It would become one per posting if the
 * batch were ever replaced by a loop, and that is what this test is for.
 *
 * <p>Expires on Day 20, when the mart moves to {@code jobs_db} and this container no longer
 * sees the statement.
 */
class SavedJobHydrationQueriesIT extends IntegrationTest {

    // Five in the mart and one that has left it, so the list is longer than two of the pages
    // below and the LEFT JOIN case is in it.
    private static final List<String> SAVED = List.of(
            "seed-0001", "seed-0002", "seed-0003", "seed-0004", "seed-0005", "republished-away");

    private ApiClient client;

    @BeforeEach
    void aUserWithMoreSavedJobsThanAPageHolds() {
        TestUser user = aUser().create();
        for (String postingId : SAVED) {
            jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                    .param("userId", user.id())
                    .param("postingId", postingId)
                    .update();
        }
        client = authenticatedAs(user);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 20})
    void readsTheMartOncePerPageWhateverItsSize(int size) {
        StatementCounter.reset();

        ApiResponse response = client.get("/api/saved-jobs?size=" + size);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/totalElements").asLong()).isEqualTo(SAVED.size());
        // The page really is that big - otherwise the sizes would all test the same thing.
        assertThat(response.at("/content").size()).isEqualTo(Math.min(size, SAVED.size()));
        assertThat(StatementCounter.statementsMentioning("fct_postings")).isEqualTo(1);
    }
}

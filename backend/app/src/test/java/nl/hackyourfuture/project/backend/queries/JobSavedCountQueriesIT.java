package nl.hackyourfuture.project.backend.queries;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StatementCounter;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many statements it takes to put {@code savedCount} on a page of postings: one, however
 * big the page.
 *
 * <p>Not a contract test. {@code JobSavedCountIT} pins the number users see; this pins how it is
 * fetched. Before Day 08 the one statement is the search query itself, which carries the count
 * as a correlated subquery. After it, the one statement is the batched count that
 * {@code applications} runs. It would become one per posting if the batch were ever replaced by
 * a loop, and that is what this test is for.
 *
 * <p>Expires on Day 25, when {@code saved_jobs} moves to its own database and this container no
 * longer sees the statement.
 */
class JobSavedCountQueriesIT extends IntegrationTest {

    // Something to count, so a count query that returns nothing cannot pass for one that works.
    @BeforeEach
    void someoneSavedAPosting() {
        TestUser user = aUser().create();
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, 'seed-0001')")
                .param("userId", user.id())
                .update();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void searchReadsSavedJobsOncePerPageWhateverItsSize(int size) {
        StatementCounter.reset();

        ApiResponse response = anonymous().get("/api/jobs?size=" + size);

        assertThat(response.status()).isEqualTo(200);
        // The page really is that big - otherwise sizes 20 and 100 would test the same thing.
        long postings = response.at("/totalElements").asLong();
        assertThat(response.at("/content").size()).isEqualTo((int) Math.min(size, postings));
        assertThat(StatementCounter.statementsMentioning("saved_jobs")).isEqualTo(1);
    }

    @Test
    void detailReadsSavedJobsOnce() {
        StatementCounter.reset();

        ApiResponse response = anonymous().get("/api/jobs/seed-0001");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/savedCount").asInt()).isEqualTo(1);
        assertThat(StatementCounter.statementsMentioning("saved_jobs")).isEqualTo(1);
    }
}

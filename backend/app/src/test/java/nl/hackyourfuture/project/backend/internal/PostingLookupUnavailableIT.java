package nl.hackyourfuture.project.backend.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubUpstream;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 19 Track A2: saved jobs survive the postings being unavailable.
 *
 * <p>With the postings service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client's fallback returns {@code {}} for the entire call. Saved jobs then lists
 * with empty details (Day 04's vanished-posting shape) and not newest first. A 4xx is a bug on the
 * caller's side and is not an outage: it is not counted by the breaker and surfaces as the
 * caller's 500.
 */
class PostingLookupUnavailableIT extends IntegrationTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("postingLookup").reset();
    }

    @Test
    void withThePostingsUnavailableSavedJobsStillListWithEmptyDetails() {
        TestUser user = aUser().create();
        String id1 = aPosting().id("unavail-1").title("First Job").create().id();
        String id2 = aPosting().id("unavail-2").title("Second Job").create().id();
        save(user, id1);
        save(user, id2);

        StubUpstream.instance().refuse("/internal/postings/batch", 503);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/saved-jobs");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/totalElements").asInt()).isEqualTo(2);
        assertThat(response.at("/content/0/postingId").asString()).isIn(id1, id2);
        assertThat(response.at("/content/0/jobState").asString()).isEqualTo("SAVED");
        assertThat(response.at("/content/0/title").isNull()).isTrue();
        assertThat(response.at("/content/0/companyName").isNull()).isTrue();
        assertThat(response.at("/content/1/postingId").asString()).isIn(id1, id2);
        assertThat(response.at("/content/1/title").isNull()).isTrue();
        assertThat(StubUpstream.instance().calls("/internal/postings/batch")).isEqualTo(1);
    }

    @Test
    void aHangFallsBackTooWithinTheReadTimeout() {
        TestUser user = aUser().create();
        String id = aPosting().id("hang-1").title("Hanging Job").create().id();
        save(user, id);

        StubUpstream.instance().hang("/internal/postings/batch");

        ApiClient client = authenticatedAs(user);
        long start = System.currentTimeMillis();
        ApiResponse response = client.get("/api/saved-jobs");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(200);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.at("/totalElements").asInt()).isEqualTo(1);
        assertThat(response.at("/content/0/title").isNull()).isTrue();

        StubUpstream.instance().reset();
    }

    @Test
    void a4xxIsABugNotAnOutage() {
        TestUser user = aUser().create();
        String id = aPosting().id("bug-1").title("Bug Job").create().id();
        save(user, id);

        StubUpstream.instance().refuse("/internal/postings/batch", 400);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/saved-jobs");

        assertThat(response.status()).isEqualTo(500);
    }

    private void save(TestUser user, String postingId) {
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }
}

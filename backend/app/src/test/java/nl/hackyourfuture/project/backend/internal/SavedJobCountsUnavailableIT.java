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
 * Day 19 Track C: job search survives the counts being unavailable.
 *
 * <p>With the applications service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client's fallback returns every requested id mapped to 0. Search and job detail
 * then answer 200 with {@code savedCount} 0. A 4xx is a bug on the caller's side and is not an
 * outage: it is not counted by the breaker and surfaces as the caller's 500.
 */
class SavedJobCountsUnavailableIT extends IntegrationTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.applications-url", () -> StubUpstream.instance().baseUrl());
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("savedJobCounts").reset();
    }

    @Test
    void withTheCountsUnavailableSearchAndDetailStillAnswerWithZero() {
        String postingId = aPosting().id("counts-1").title("Counts Unavailable 1").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/saved-counts", 503);

        ApiClient client = anonymous();
        ApiResponse search = client.get("/api/jobs?q={q}", "Counts Unavailable 1");
        assertThat(search.status()).isEqualTo(200);
        assertThat(search.at("/content/0/postingId").asString()).isEqualTo(postingId);
        assertThat(search.at("/content/0/savedCount").asInt()).isZero();
        ApiResponse detail = client.get("/api/jobs/" + postingId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.at("/savedCount").asInt()).isZero();
    }

    @Test
    void aHangFallsBackToZeroWithinTheReadTimeout() {
        String postingId = aPosting().id("counts-2").title("Counts Unavailable 2").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().hang("/internal/saved-counts");

        ApiClient client = anonymous();
        long start = System.currentTimeMillis();
        ApiResponse response = client.get("/api/jobs/" + postingId);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(200);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.at("/savedCount").asInt()).isZero();

        StubUpstream.instance().reset();
    }

    @Test
    void a4xxIsABugNotAnOutage() {
        String postingId = aPosting().id("counts-3").title("Counts Unavailable 3").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/saved-counts", 400);

        ApiClient client = anonymous();
        ApiResponse response = client.get("/api/jobs/" + postingId);

        assertThat(response.status()).isEqualTo(500);
    }

    private void save(TestUser user, String postingId) {
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }
}

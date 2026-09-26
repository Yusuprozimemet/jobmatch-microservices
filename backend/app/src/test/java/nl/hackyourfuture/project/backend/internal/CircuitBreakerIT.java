package nl.hackyourfuture.project.backend.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubUpstream;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monolith's own {@code postingLookup} breaker: it opens after five failures and recovers
 * when the upstream answers; it ignores a 4xx (a bug, not an outage). With {@code record-exceptions}
 * alone a 4xx would count as a success and dilute the failure rate; {@code ignore-exceptions}
 * stops it being recorded. The counts breaker is tested where the counts client lives.
 *
 * <p>The breaker is configured with 10 calls, at least 5 failures, 50 % open, 10 s open wait
 * (1 s here). Tests drive everything through {@code GET /api/saved-jobs}, which calls
 * {@code /internal/postings/batch} through the posting lookup client.
 */
class CircuitBreakerIT extends IntegrationTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
        registry.add("resilience4j.circuitbreaker.instances.postingLookup.wait-duration-in-open-state", () -> "1s");
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("postingLookup").reset();
    }

    @Test
    void itOpensAfterFiveFailuresAndTheCallersGetTheFallbackAtOnce() {
        String postingId = aPosting().id("breaker-open").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/postings/batch", 503);

        List<Integer> callCounts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ApiResponse response = authenticatedAs(user).get("/api/saved-jobs");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/totalElements").asInt()).isEqualTo(1);
            assertThat(response.at("/content/0/title").isNull()).isTrue();

            callCounts.add(StubUpstream.instance().calls("/internal/postings/batch"));
        }

        assertThat(callCounts).containsExactly(1, 2, 3, 4, 5, 5, 5, 5);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(OPEN);
    }

    @Test
    void itRecoversByItselfOnceTheUpstreamAnswers() throws InterruptedException {
        String postingId = aPosting().id("breaker-recover").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/postings/batch", 503);
        for (int i = 0; i < 5; i++) {
            authenticatedAs(user).get("/api/saved-jobs");
        }

        StubUpstream.instance().answer("/internal/postings/batch", 200, "{\"" + postingId + "\":{\"title\":\"Test Posting\"}}");

        Thread.sleep(1200);

        int callsBeforeRecovery = StubUpstream.instance().calls("/internal/postings/batch");

        for (int i = 0; i < 2; i++) {
            ApiResponse response = authenticatedAs(user).get("/api/saved-jobs");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/content/0/title").asText()).isEqualTo("Test Posting");
        }

        assertThat(StubUpstream.instance().calls("/internal/postings/batch")).isEqualTo(callsBeforeRecovery + 2);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(CLOSED);
    }

    @Test
    void itIgnoresA4xx() {
        String postingId = aPosting().id("breaker-4xx").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/postings/batch", 400);

        for (int i = 0; i < 6; i++) {
            ApiResponse response = authenticatedAs(user).get("/api/saved-jobs");
            assertThat(response.status()).isEqualTo(500);
        }

        assertThat(StubUpstream.instance().calls("/internal/postings/batch")).isEqualTo(6);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(CLOSED);
        assertThat(registry.circuitBreaker("postingLookup").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private void save(TestUser user, String postingId) {
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }
}

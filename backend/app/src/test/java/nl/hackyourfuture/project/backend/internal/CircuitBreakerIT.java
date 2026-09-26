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
 * Day 19 Track D: the circuit breaker opens after five failures and recovers when the upstream
 * answers; it ignores a 4xx (a bug, not an outage). With {@code record-exceptions} alone a 4xx
 * would count as a success and dilute the failure rate; {@code ignore-exceptions} stops it being
 * recorded.
 *
 * <p>The breaker is configured with 10 calls, at least 5 failures, 50 % open, 10 s open wait
 * (1 s here). Tests drive everything through {@code GET /api/jobs/{postingId}}, which uses the
 * counts client.
 */
class CircuitBreakerIT extends IntegrationTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.applications-url", () -> StubUpstream.instance().baseUrl());
        registry.add("resilience4j.circuitbreaker.instances.savedJobCounts.wait-duration-in-open-state", () -> "1s");
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("savedJobCounts").reset();
    }

    @Test
    void itOpensAfterFiveFailuresAndTheCallersGetTheFallbackAtOnce() {
        String postingId = aPosting().id("breaker-open").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/saved-counts", 503);

        List<Integer> callCounts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ApiResponse response = anonymous().get("/api/jobs/" + postingId);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/savedCount").asInt()).isZero();

            callCounts.add(StubUpstream.instance().calls("/internal/saved-counts"));
        }

        assertThat(callCounts).containsExactly(1, 2, 3, 4, 5, 5, 5, 5);
        assertThat(registry.circuitBreaker("savedJobCounts").getState()).isEqualTo(OPEN);
    }

    @Test
    void itRecoversByItselfOnceTheUpstreamAnswers() throws InterruptedException {
        String postingId = aPosting().id("breaker-recover").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/saved-counts", 503);
        for (int i = 0; i < 5; i++) {
            anonymous().get("/api/jobs/" + postingId);
        }

        StubUpstream.instance().answer("/internal/saved-counts", 200, "{\"" + postingId + "\":0}");

        Thread.sleep(1200);

        int callsBeforeRecovery = StubUpstream.instance().calls("/internal/saved-counts");

        for (int i = 0; i < 2; i++) {
            ApiResponse response = anonymous().get("/api/jobs/" + postingId);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/savedCount").asInt()).isZero();
        }

        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(callsBeforeRecovery + 2);
        assertThat(registry.circuitBreaker("savedJobCounts").getState()).isEqualTo(CLOSED);
    }

    @Test
    void itIgnoresA4xx() {
        String postingId = aPosting().id("breaker-4xx").create().id();
        TestUser user = aUser().create();
        save(user, postingId);

        StubUpstream.instance().refuse("/internal/saved-counts", 400);

        for (int i = 0; i < 6; i++) {
            ApiResponse response = anonymous().get("/api/jobs/" + postingId);
            assertThat(response.status()).isEqualTo(500);
        }

        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(6);
        assertThat(registry.circuitBreaker("savedJobCounts").getState()).isEqualTo(CLOSED);
        assertThat(registry.circuitBreaker("savedJobCounts").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private void save(TestUser user, String postingId) {
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }
}

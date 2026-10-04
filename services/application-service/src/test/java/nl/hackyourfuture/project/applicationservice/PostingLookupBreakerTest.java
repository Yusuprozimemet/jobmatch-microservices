package nl.hackyourfuture.project.applicationservice;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code postingLookup} breaker: it opens after five failures and recovers when the upstream
 * answers; it ignores a 4xx (a bug, not an outage). With {@code record-exceptions} alone a 4xx
 * would count as a success and dilute the failure rate; {@code ignore-exceptions} stops it being
 * recorded.
 *
 * <p>The breaker is configured with 10 calls, at least 5 failures, 50 % open, 10 s open wait
 * (1 s here). Tests drive everything through {@code GET /api/saved-jobs}, which calls
 * {@code /internal/postings/batch} through the posting lookup client.
 *
 * <p>Moved from the monolith's {@code CircuitBreakerIT} on Day 25.
 */
class PostingLookupBreakerTest extends ApplicationServiceTest {

    private static final String BATCH = "/internal/postings/batch";

    @Autowired
    private CircuitBreakerRegistry registry;

    @LocalServerPort
    private int port;

    private SavedJobsCalls calls;

    @DynamicPropertySource
    static void shortOpenWait(DynamicPropertyRegistry registry) {
        registry.add("resilience4j.circuitbreaker.instances.postingLookup.wait-duration-in-open-state", () -> "1s");
    }

    @BeforeEach
    void resetStubAndBreaker() throws Exception {
        StubUpstream.instance().reset();
        registry.circuitBreaker("postingLookup").reset();
        SavedJobsCalls.deleteAll();
        calls = new SavedJobsCalls(port);
    }

    @Test
    void itOpensAfterFiveFailuresAndTheCallersGetTheFallbackAtOnce() throws Exception {
        UUID user = UUID.randomUUID();
        SavedJobsCalls.save(user, "breaker-open");

        StubUpstream.instance().refuse(BATCH, 503);

        List<Integer> callCounts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SavedJobsCalls.Response response = calls.savedJobs(user);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/totalElements").asInt()).isEqualTo(1);
            assertThat(response.at("/content/0/title").isNull()).isTrue();

            callCounts.add(StubUpstream.instance().calls(BATCH));
        }

        assertThat(callCounts).containsExactly(1, 2, 3, 4, 5, 5, 5, 5);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(OPEN);
    }

    @Test
    void itRecoversByItselfOnceTheUpstreamAnswers() throws Exception {
        UUID user = UUID.randomUUID();
        String postingId = "breaker-recover";
        SavedJobsCalls.save(user, postingId);

        StubUpstream.instance().refuse(BATCH, 503);
        for (int i = 0; i < 5; i++) {
            calls.savedJobs(user);
        }

        StubUpstream.instance().answer(BATCH, 200, "{\"" + postingId + "\":{\"title\":\"Test Posting\"}}");

        Thread.sleep(1200);

        int callsBeforeRecovery = StubUpstream.instance().calls(BATCH);

        for (int i = 0; i < 2; i++) {
            SavedJobsCalls.Response response = calls.savedJobs(user);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/content/0/title").asString()).isEqualTo("Test Posting");
        }

        assertThat(StubUpstream.instance().calls(BATCH)).isEqualTo(callsBeforeRecovery + 2);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(CLOSED);
    }

    @Test
    void itIgnoresA4xx() throws Exception {
        UUID user = UUID.randomUUID();
        SavedJobsCalls.save(user, "breaker-4xx");

        StubUpstream.instance().refuse(BATCH, 400);

        for (int i = 0; i < 6; i++) {
            assertThat(calls.savedJobs(user).status()).isEqualTo(500);
        }

        assertThat(StubUpstream.instance().calls(BATCH)).isEqualTo(6);
        assertThat(registry.circuitBreaker("postingLookup").getState()).isEqualTo(CLOSED);
        assertThat(registry.circuitBreaker("postingLookup").getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}

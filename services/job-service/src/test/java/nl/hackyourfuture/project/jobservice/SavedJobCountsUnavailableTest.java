package nl.hackyourfuture.project.jobservice;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 17 Track E1: job-service's counts client survives the monolith being unavailable.
 *
 * <p>With the monolith refusing a request (a timeout, a 5xx, or the breaker open), the client's
 * fallback returns every distinct requested id mapped to 0. A connection failure is the same
 * {@code ResourceAccessException} as the timeout. A 4xx is a bug on the caller's side and is not an
 * outage: it is rethrown, not recorded by the breaker, and the caller answers 500.
 *
 * <p>The cases of the monolith's {@code SavedJobCountsUnavailableIT}, which Track D removed: across
 * the harness container, its failures would open the breaker every other test shares. Here the
 * client and its breaker are this context's own, against a local stub, with no database.
 */
class SavedJobCountsUnavailableTest extends JobServiceTest {

    @Autowired
    private SavedJobCounts counts;

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
    void anAnswerPassesThrough() {
        StubUpstream.instance().answer("/internal/saved-counts", 200, "{\"p1\":3,\"p2\":1}");

        Map<String, Integer> result = counts.countsFor(List.of("p1", "p2"));

        assertThat(result).isEqualTo(Map.of("p1", 3, "p2", 1));
        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(1);
    }

    @Test
    void noIdsMeansNoCall() {
        Map<String, Integer> result = counts.countsFor(List.of());

        assertThat(result).isEmpty();
        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(0);
    }

    @Test
    void a5xxFallsBackToZeroForEveryDistinctId() {
        StubUpstream.instance().refuse("/internal/saved-counts", 503);

        Map<String, Integer> result = counts.countsFor(List.of("p1", "p2", "p1"));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("p1", 0, "p2", 0));
        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(1);
    }

    @Test
    void aHangFallsBackToZeroWithinTheReadTimeout() {
        StubUpstream.instance().hang("/internal/saved-counts");

        long start = System.currentTimeMillis();
        Map<String, Integer> result = counts.countsFor(List.of("p1"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo(Map.of("p1", 0));
        assertThat(elapsed).isLessThan(3000);
    }

    @Test
    void a4xxIsABugNotAnOutage() {
        StubUpstream.instance().refuse("/internal/saved-counts", 400);
        CircuitBreaker breaker = registry.circuitBreaker("savedJobCounts");

        // Six, more than the breaker's minimum of five calls.
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> counts.countsFor(List.of("p1")))
                    .isInstanceOf(HttpClientErrorException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
    }

    @Test
    void fiveFailuresOpenTheBreakerAndItAnswersZeroWithoutCalling() {
        StubUpstream.instance().refuse("/internal/saved-counts", 503);
        CircuitBreaker breaker = registry.circuitBreaker("savedJobCounts");

        for (int i = 0; i < 5; i++) {
            Map<String, Integer> result = counts.countsFor(List.of("p1"));
            assertThat(result).isEqualTo(Map.of("p1", 0));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(5);

        // Open: the fallback answers, and no request is sent.
        Map<String, Integer> result = counts.countsFor(List.of("p1"));
        assertThat(result).isEqualTo(Map.of("p1", 0));
        assertThat(StubUpstream.instance().calls("/internal/saved-counts")).isEqualTo(5);
    }
}

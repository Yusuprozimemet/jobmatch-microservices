package nl.hackyourfuture.project.matchingservice;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * matching's three internal clients get the monolith's breaker (Day 19), and the aspect that
 * applies it is wired: without {@code spring-boot-starter-aspectj}, {@code @CircuitBreaker} does
 * nothing and no error says so. A breaker the yaml does not name gets resilience4j's defaults (a
 * window of 100), so each instance's config is read, not only its existence.
 */
class BreakerConfigTest extends MatchingServiceTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BreakerTestComponent testComponent;

    @ParameterizedTest
    @ValueSource(strings = {"profileDirectory", "userExistence", "postingShortlist"})
    void breakerIsConfiguredAsTheSpecSays(String name) {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(name);
        var config = breaker.getCircuitBreakerConfig();

        // Check the sliding window configuration
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);

        // Verify record predicate - these exceptions should be recorded
        var recordPredicate = config.getRecordExceptionPredicate();
        assertThat(recordPredicate.test(new ResourceAccessException("timeout"))).isTrue();
        assertThat(recordPredicate.test(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", null, null, null))).isTrue();

        // Verify ignore predicate - client errors should be ignored
        var ignorePredicate = config.getIgnoreExceptionPredicate();
        assertThat(ignorePredicate.test(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request", null, null, null))).isTrue();
    }

    @Test
    void aBreakerOpensAfterFiveFailures() {
        // Simulate 5 failures to trigger the open state (with minimum 5 calls required)
        for (int i = 0; i < 5; i++) {
            try {
                testComponent.callThatThrows();
            } catch (ResourceAccessException e) {
                // Expected
            }
        }

        // The breaker should be OPEN now, so the next call throws CallNotPermittedException
        assertThatThrownBy(testComponent::callThatThrows)
                .isInstanceOf(CallNotPermittedException.class);

    }

    @AfterEach
    void resetBreakers() {
        circuitBreakerRegistry.circuitBreaker("profileDirectory").reset();
        circuitBreakerRegistry.circuitBreaker("userExistence").reset();
        circuitBreakerRegistry.circuitBreaker("postingShortlist").reset();
    }
}

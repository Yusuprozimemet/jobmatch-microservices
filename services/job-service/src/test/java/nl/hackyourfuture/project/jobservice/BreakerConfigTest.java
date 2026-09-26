package nl.hackyourfuture.project.jobservice;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the savedJobCounts circuit breaker is configured as the spec requires.
 */
class BreakerConfigTest extends JobServiceTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void savedJobCountsBreakerIsConfiguredAsTheSpecSays() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("savedJobCounts");
        assertThat(breaker).isNotNull();

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
}

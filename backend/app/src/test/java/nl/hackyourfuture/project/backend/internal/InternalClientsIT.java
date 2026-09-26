package nl.hackyourfuture.project.backend.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubUpstream;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 19 Track A1: the builder that makes every internal client.
 *
 * <p>Verifies the URL resolution (empty property or explicit), the timeouts, the service token
 * header, and that the circuit breakers are wired as the spec specifies.
 */
class InternalClientsIT extends IntegrationTest {

    @Autowired
    private InternalClients internalClients;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void stubUpstream(DynamicPropertyRegistry registry) {
        // Do not set app.internal.* here: the tests below read test.internal.unset-url, which
        // nothing sets, so it means this process whatever the harness points the others at.
        registry.add("test.internal.stub-url", () -> StubUpstream.instance().baseUrl());
    }

    @BeforeEach
    void resetStub() {
        StubUpstream.instance().reset();
    }

    @Test
    void emptyPropertyMeansThisProcessWithAServiceToken() {
        RestClient client = internalClients.forUrlProperty("test.internal.unset-url").get();

        // The internal /users/{id} route answers 204 with the token, 401 without.
        TestUser user = aUser().create();
        var response = client.get()
                .uri("/internal/users/{id}", user.id())
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void propertyPointsTheClientElsewhere() {
        StubUpstream.instance().answer("/ping", 200, "{}");

        RestClient client = internalClients.forUrlProperty("test.internal.stub-url").get();
        client.get()
                .uri("/ping")
                .retrieve()
                .toBodilessEntity();

        assertThat(StubUpstream.instance().calls("/ping")).isEqualTo(1);
    }

    @Test
    void hangIsAResourceAccessExceptionWithinTheReadTimeout() {
        StubUpstream.instance().hang("/slow");
        RestClient client = internalClients.forUrlProperty("test.internal.stub-url").get();

        Instant start = Instant.now();
        assertThatThrownBy(() -> {
            client.post()
                    .uri("/slow")
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
        })
                .isInstanceOf(ResourceAccessException.class);

        long elapsedMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        assertThat(elapsedMs).isLessThan(3000);

        StubUpstream.instance().reset();
    }

    @Test
    void fourXxAndFiveXxAreTheirOwnExceptions() {
        StubUpstream.instance().refuse("/bad", 400);
        StubUpstream.instance().refuse("/down", 503);

        RestClient client = internalClients.forUrlProperty("test.internal.stub-url").get();

        assertThatThrownBy(() -> client.get()
                .uri("/bad")
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class);

        assertThatThrownBy(() -> client.get()
                .uri("/down")
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void sameClientIsReturnedEveryTime() {
        var supplier = internalClients.forUrlProperty("test.internal.unset-url");
        RestClient first = supplier.get();
        RestClient second = supplier.get();

        assertThat(first).isSameAs(second);
    }

    @Test
    void breakersAreConfiguredAsTheSpecSays() {
        var postingLookupBreaker = circuitBreakerRegistry.circuitBreaker("postingLookup");
        var postingShortlistBreaker = circuitBreakerRegistry.circuitBreaker("postingShortlist");
        var savedJobCountsBreaker = circuitBreakerRegistry.circuitBreaker("savedJobCounts");

        for (var breaker : new Object[]{postingLookupBreaker, postingShortlistBreaker, savedJobCountsBreaker}) {
            var config = ((io.github.resilience4j.circuitbreaker.CircuitBreaker) breaker).getCircuitBreakerConfig();

            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);

            // Verify record predicate
            var recordPredicate = config.getRecordExceptionPredicate();
            assertThat(recordPredicate.test(new ResourceAccessException("x"))).isTrue();
            assertThat(recordPredicate.test(HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable", null, null, null))).isTrue();

            // Verify ignore predicate
            var ignorePredicate = config.getIgnoreExceptionPredicate();
            assertThat(ignorePredicate.test(HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request", null, null, null))).isTrue();
        }
    }
}

package nl.hackyourfuture.project.backend.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.StubUpstream;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 19 Track B: top matches fail fast and say so when the postings are unavailable.
 *
 * <p>With the postings service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client throws a 503: matching without postings is meaningless, and the answer is
 * never an empty list, which would be indistinguishable from "no matches found". A 4xx is a bug on
 * the caller's side and is not an outage: it is not counted by the breaker and surfaces as the
 * caller's 500.
 */
class PostingShortlistUnavailableIT extends MatchingTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("postingShortlist").reset();
    }

    @Test
    void aHangingShortlistIsA503WithinTheReadTimeout() {
        TestUser user = userWithProfile();
        posting("hang-1", "Hanging Job", "java", "sql");

        StubUpstream.instance().hang("/internal/postings/shortlist");

        ApiClient client = authenticatedAs(user);
        long start = System.currentTimeMillis();
        ApiResponse response = client.get("/api/jobs/top-matches");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(503);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.at("/detail").asString()).contains("The postings could not be reached");

        StubUpstream.instance().reset();
    }

    @Test
    void anUnavailableShortlistIsA503NotAnEmptyList() {
        TestUser user = userWithProfile();
        posting("unavail-1", "Unavailable Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/postings/shortlist", 503);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.at("/detail").asString()).contains("The postings could not be reached");
    }

    @Test
    void a4xxIsABugNotAnOutage() {
        TestUser user = userWithProfile();
        posting("bug-1", "Bug Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/postings/shortlist", 400);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(500);
    }
}

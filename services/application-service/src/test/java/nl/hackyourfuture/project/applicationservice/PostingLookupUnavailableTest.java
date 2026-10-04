package nl.hackyourfuture.project.applicationservice;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 19 Track A2: saved jobs survive the postings being unavailable.
 *
 * <p>With the postings service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client's fallback returns {@code {}} for the entire call. Saved jobs then lists
 * with empty details (Day 04's vanished-posting shape) and not newest first. A 4xx is a bug on the
 * caller's side and is not an outage: it is not counted by the breaker and surfaces as the
 * caller's 500.
 *
 * <p>Moved from the monolith on Day 25.
 */
class PostingLookupUnavailableTest extends ApplicationServiceTest {

    private static final String BATCH = "/internal/postings/batch";

    @Autowired
    private CircuitBreakerRegistry registry;

    @LocalServerPort
    private int port;

    private SavedJobsCalls calls;

    @BeforeEach
    void resetStubAndBreaker() throws Exception {
        StubUpstream.instance().reset();
        registry.circuitBreaker("postingLookup").reset();
        SavedJobsCalls.deleteAll();
        calls = new SavedJobsCalls(port);
    }

    @Test
    void withThePostingsUnavailableSavedJobsStillListWithEmptyDetails() throws Exception {
        UUID user = UUID.randomUUID();
        String id1 = "unavail-1";
        String id2 = "unavail-2";
        SavedJobsCalls.save(user, id1);
        SavedJobsCalls.save(user, id2);

        StubUpstream.instance().refuse(BATCH, 503);

        SavedJobsCalls.Response response = calls.savedJobs(user);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/totalElements").asInt()).isEqualTo(2);
        assertThat(response.at("/content/0/postingId").asString()).isIn(id1, id2);
        assertThat(response.at("/content/0/jobState").asString()).isEqualTo("SAVED");
        assertThat(response.at("/content/0/title").isNull()).isTrue();
        assertThat(response.at("/content/0/companyName").isNull()).isTrue();
        assertThat(response.at("/content/1/postingId").asString()).isIn(id1, id2);
        assertThat(response.at("/content/1/title").isNull()).isTrue();
        assertThat(StubUpstream.instance().calls(BATCH)).isEqualTo(1);
    }

    @Test
    void aHangFallsBackTooWithinTheReadTimeout() throws Exception {
        UUID user = UUID.randomUUID();
        SavedJobsCalls.save(user, "hang-1");

        StubUpstream.instance().hang(BATCH);

        long start = System.currentTimeMillis();
        SavedJobsCalls.Response response = calls.savedJobs(user);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(200);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.at("/totalElements").asInt()).isEqualTo(1);
        assertThat(response.at("/content/0/title").isNull()).isTrue();

        StubUpstream.instance().reset();
    }

    @Test
    void a4xxIsABugNotAnOutage() throws Exception {
        UUID user = UUID.randomUUID();
        SavedJobsCalls.save(user, "bug-1");

        StubUpstream.instance().refuse(BATCH, 400);

        assertThat(calls.savedJobs(user).status()).isEqualTo(500);
    }
}

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
 * Day 41 Tracks B and C: top matches fail fast and say so when the profile or user is
 * unavailable.
 *
 * <p>With the identity service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client throws a 503: a profile is needed to rank. A 404 (no profile) returns
 * 422 "Fill in your profile", the same answer as a missing account. A 4xx is a bug on the
 * caller's side and is not an outage: it is not counted by the breaker and surfaces as the
 * caller's 500.
 */
class ProfileDirectoryUnavailableIT extends MatchingTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.internal.identity-url", () -> StubUpstream.instance().baseUrl());
    }

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        registry.circuitBreaker("profileDirectory").reset();
        registry.circuitBreaker("userExistence").reset();
    }

    private void known(TestUser user) {
        StubUpstream.instance().refuse("/internal/users/" + user.id(), 204);
    }

    @Test
    void anUnreachableProfileIsA503() {
        TestUser user = aUser().create();
        posting("profile-unavail-1", "Unavailable Job", "java", "sql");

        known(user);
        StubUpstream.instance().refuse("/internal/profiles/" + user.id(), 503);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.at("/detail").asString()).contains("The profile could not be reached");
    }

    @Test
    void aHangingProfileIsA503WithinTheReadTimeout() {
        TestUser user = aUser().create();
        posting("profile-hang-1", "Hanging Job", "java", "sql");

        known(user);
        StubUpstream.instance().hang("/internal/profiles/" + user.id());

        ApiClient client = authenticatedAs(user);
        long start = System.currentTimeMillis();
        ApiResponse response = client.get("/api/jobs/top-matches");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.status()).isEqualTo(503);
        assertThat(elapsed).isLessThan(5000);
        assertThat(response.at("/detail").asString()).contains("The profile could not be reached");

        StubUpstream.instance().reset();
    }

    @Test
    void noProfileIsA422() {
        TestUser user = aUser().create();
        posting("profile-none-1", "No Profile Job", "java", "sql");

        known(user);
        StubUpstream.instance().refuse("/internal/profiles/" + user.id(), 404);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.at("/detail").asString()).contains("Fill in your profile");
        // The user has no profile in this process either: only the call says the client answered.
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + user.id())).isEqualTo(1);
    }

    @Test
    void aProfileFromIdentityIsRankedOn() {
        TestUser user = aUser().create();
        posting("stub-profile-1", "Stub Profile Engineer", "java", "sql");

        known(user);
        StubUpstream.instance().answer("/internal/profiles/" + user.id(), 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":null}");

        ApiClient client = authenticatedAs(user);
        model().willScoreInPromptOrder(80);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("stub-profile-1");
    }

    @Test
    void a4xxIsABugNotAnOutage() {
        TestUser user = aUser().create();
        posting("profile-bug-1", "Bug Job", "java", "sql");

        known(user);
        StubUpstream.instance().refuse("/internal/profiles/" + user.id(), 400);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(500);
    }

    @Test
    void aUserIdentityDoesNotKnowIsA422() {
        TestUser user = aUser().create();
        posting("unknown-user-1", "Unknown User Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/users/" + user.id(), 404);
        StubUpstream.instance().answer("/internal/profiles/" + user.id(), 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":null}");

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.at("/detail").asString()).contains("Fill in your profile");
        assertThat(StubUpstream.instance().calls("/internal/users/" + user.id())).isEqualTo(1);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + user.id())).isEqualTo(0);
    }

    @Test
    void anUnreachableIdentityIsA503() {
        TestUser user = aUser().create();
        posting("unreachable-identity-1", "Unreachable Identity Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/users/" + user.id(), 503);

        ApiClient client = authenticatedAs(user);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.at("/detail").asString()).contains("Your account could not be checked");
    }
}

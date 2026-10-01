package nl.hackyourfuture.project.matchingservice;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Top matches fail fast and say so when the profile or user is unavailable. Moved from the
 * monolith on Day 21.
 *
 * <p>With the identity service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client throws a 503: a profile is needed to rank. A 404 (no profile) returns
 * 422 "Fill in your profile", the same answer as a missing account. A 4xx is a bug on the
 * caller's side and is not an outage: it is not counted by the breaker and surfaces as a 500.
 *
 * <p>The existence call refused or hanging is a 503 too, within the 1 s connect and 2 s read
 * timeouts. Its answer is never cached ({@code InternalUserController} says why), so every request
 * asks. Those tests stub everything after the existence call, so that an existence client that
 * took a failure for "exists" would answer 200.
 */
class ProfileDirectoryUnavailableTest extends MatchingServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String CITY = "testville";

    @LocalServerPort
    private int port;

    @Autowired
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void resetStubAndBreaker() {
        StubUpstream.instance().reset();
        StubLlm.instance().reset();
        registry.circuitBreaker("profileDirectory").reset();
        registry.circuitBreaker("userExistence").reset();
    }

    @Test
    void anUnreachableProfileIsA503() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("profile-unavail-1", "Unavailable Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/profiles/" + userId, 503);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("The profile could not be reached");
    }

    @Test
    void aHangingProfileIsA503WithinTheReadTimeout() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("profile-hang-1", "Hanging Job", "java", "sql");

        StubUpstream.instance().hang("/internal/profiles/" + userId);

        long start = System.currentTimeMillis();
        HttpResponse<String> response = topMatches(userId);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(elapsed).isLessThan(5000);
        assertThat(response.body()).contains("The profile could not be reached");

        StubUpstream.instance().reset();
    }

    @Test
    void noProfileIsA422() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("profile-none-1", "No Profile Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/profiles/" + userId, 404);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("Fill in your profile");
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(1);
    }

    @Test
    void aProfileFromIdentityIsRankedOn() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("stub-profile-1", "Stub Profile Engineer", "java", "sql");

        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");

        StubLlm.instance().willScoreInPromptOrder(80);
        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("stub-profile-1");
    }

    @Test
    void a4xxIsABugNotAnOutage() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("profile-bug-1", "Bug Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/profiles/" + userId, 400);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(500);
    }

    @Test
    void aUserIdentityDoesNotKnowIsA422() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        stubPosting("unknown-user-1", "Unknown User Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/users/" + userId, 404);
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("Fill in your profile");
        assertThat(StubUpstream.instance().calls("/internal/users/" + userId)).isEqualTo(1);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(0);
    }

    @Test
    void anUnreachableIdentityIsA503() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        stubPosting("unreachable-identity-1", "Unreachable Identity Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/users/" + userId, 503);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Your account could not be checked");
    }

    @Test
    void aRefusedIdentityIsA503() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        rankable(userId, "refused-identity-1", "Refused Identity Job");

        StubUpstream.instance().drop("/internal/users/" + userId);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Your account could not be checked");
    }

    @Test
    void aHangingIdentityIsA503WithinTheTimeouts() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        rankable(userId, "hanging-identity-1", "Hanging Identity Job");

        StubUpstream.instance().hang("/internal/users/" + userId);

        long start = System.currentTimeMillis();
        HttpResponse<String> response = topMatches(userId);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.body()).contains("Your account could not be checked");

        StubUpstream.instance().reset();
    }

    @Test
    void theExistenceAnswerIsNotCached() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        rankable(userId, "not-cached-1", "Not Cached Job");

        HttpResponse<String> first = topMatches(userId);
        HttpResponse<String> second = topMatches(userId);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/users/" + userId)).isEqualTo(2);
    }

    /** Everything after the existence call answers: a posting, a five-skill profile and the model. */
    private void rankable(UUID userId, String postingId, String title) {
        stubPosting(postingId, title, "java", "sql");
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
        StubLlm.instance().willScoreInPromptOrder(80);
    }

    private void knownUser(UUID userId) {
        StubUpstream.instance().refuse("/internal/users/" + userId, 204);
    }

    private void stubPosting(String id, String title, String... skills) {
        String skillsJson = "[" + String.join(",", skills).replace("java", "\"java\"")
                .replace("sql", "\"sql\"").replace("docker", "\"docker\"")
                .replace("git", "\"git\"").replace("linux", "\"linux\"") + "]";
        String postingJson = "[{" +
                "\"postingId\":\"" + id + "\"," +
                "\"title\":\"" + title + "\"," +
                "\"company\":\"Company " + id + "\"," +
                "\"location\":\"" + CITY + "\"," +
                "\"category\":null," +
                "\"postedDate\":\"2026-09-30\"," +
                "\"jobSkills\":" + skillsJson + "," +
                "\"matchedSkills\":" + skillsJson + "," +
                "\"jobSkillCount\":" + skills.length +
                "}]";
        StubUpstream.instance().answer("/internal/postings/shortlist", 200, postingJson);
    }

    private HttpResponse<String> topMatches(UUID userId) throws IOException, InterruptedException {
        String token = TestIdentity.instance().token(userId);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs/top-matches"))
                .GET()
                .header("Cookie", "access_token=" + token)
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

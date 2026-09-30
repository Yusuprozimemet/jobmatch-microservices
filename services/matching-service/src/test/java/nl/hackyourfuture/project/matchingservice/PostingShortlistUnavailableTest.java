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
 * Top matches fail fast and say so when the postings are unavailable. Moved from the monolith
 * on Day 21.
 *
 * <p>With the postings service refusing a request (connection failure, timeout, 5xx, or open
 * breaker), the client throws a 503: matching without postings is meaningless, and the answer is
 * never an empty list, which would be indistinguishable from "no matches found". A 4xx is a bug on
 * the caller's side and is not an outage: it is not counted by the breaker and surfaces as a 500.
 */
class PostingShortlistUnavailableTest extends MatchingServiceTest {

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
        registry.circuitBreaker("postingShortlist").reset();
    }

    @Test
    void aHangingShortlistIsA503WithinTheReadTimeout() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        stubProfile(userId);
        stubPosting("hang-1", "Hanging Job", "java", "sql");

        StubUpstream.instance().hang("/internal/postings/shortlist");

        long start = System.currentTimeMillis();
        HttpResponse<String> response = topMatches(userId);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(elapsed).isLessThan(3000);
        assertThat(response.body()).contains("The postings could not be reached");

        StubUpstream.instance().reset();
    }

    @Test
    void anUnavailableShortlistIsA503NotAnEmptyList() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        stubProfile(userId);
        stubPosting("unavail-1", "Unavailable Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/postings/shortlist", 503);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("The postings could not be reached");
    }

    @Test
    void a4xxIsABugNotAnOutage() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        stubProfile(userId);
        stubPosting("bug-1", "Bug Job", "java", "sql");

        StubUpstream.instance().refuse("/internal/postings/shortlist", 400);

        HttpResponse<String> response = topMatches(userId);

        assertThat(response.statusCode()).isEqualTo(500);
    }

    private void stubProfile(UUID userId) {
        StubUpstream.instance().refuse("/internal/users/" + userId, 204);
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
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

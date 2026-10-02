package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user.deleted consumer evicts a user's cached profile from matching-service (Day 27).
 * The profile cache window is 1 h so that cache expiry can never be what refetches the profile;
 * the test polls until it sees the eviction.
 */
@TestPropertySource(properties = {"test.events.consumer.enabled=true", "app.profile-cache.window=1h"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserDeletedConsumerTest extends MatchingServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String CITY = "testville";
    private static final JsonMapper JSON = new JsonMapper();
    private static final long WAIT_MILLIS = 5_000;
    private static final String QUEUE_NAME = "matching-consumer-test-" + UUID.randomUUID();
    private static String queueUrl;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void useOwnQueue(DynamicPropertyRegistry registry) {
        queueUrl = SqsContainer.createOwnQueue(QUEUE_NAME);
        registry.add("app.events.sqs.endpoint", () -> SqsContainer.endpoint());
        registry.add("app.events.sqs.access-key", () -> "dummy");
        registry.add("app.events.sqs.secret-key", () -> "dummy");
        registry.add("app.events.user-deleted-queue-url", () -> queueUrl);
    }

    @BeforeEach
    void resetStubs() {
        StubUpstream.instance().reset();
        StubLlm.instance().reset();
    }

    @Test
    void theEventEvictsThatUsersCachedProfile() throws IOException, InterruptedException {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        knownUser(userA);
        knownUser(userB);
        rankable(userA, "evict-test-1", "Evict Test Job");
        rankable(userB, "evict-test-2", "Evict Test Job 2");

        HttpResponse<String> firstA = topMatches(userA);
        assertThat(firstA.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userA)).isEqualTo(1);

        HttpResponse<String> firstB = topMatches(userB);
        assertThat(firstB.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userB)).isEqualTo(1);

        sendEvent(userA);

        // Poll and call topMatches until the profile is refetched (eviction happened)
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (StubUpstream.instance().calls("/internal/profiles/" + userA) < 2
                && System.currentTimeMillis() < deadline) {
            topMatches(userA);
            Thread.sleep(100);
        }

        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userA)).isEqualTo(2);
        HttpResponse<String> secondB = topMatches(userB);
        assertThat(secondB.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userB)).isEqualTo(1);
    }

    private void sendEvent(UUID userId) {
        sendRaw(eventBody(userId));
    }

    private void sendRaw(String body) {
        SqsContainer.sqs().sendMessage(r -> r
                .queueUrl(queueUrl)
                .messageBody(body));
    }

    private String eventBody(UUID userId) {
        return envelope(UUID.randomUUID().toString(), "user.deleted", 1, userId.toString());
    }

    private static String envelope(String eventId, String type, Object version, String userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("version", version);
        event.put("userId", userId);
        event.put("occurredAt", Instant.now().toString());
        return JSON.writeValueAsString(event);
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

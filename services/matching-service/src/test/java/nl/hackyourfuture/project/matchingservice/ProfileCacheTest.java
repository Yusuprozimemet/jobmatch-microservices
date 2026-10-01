package nl.hackyourfuture.project.matchingservice;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 24 Track B: a second request within the window makes no profile call, one after it does,
 * and the existence call is made every time. Only a rankable profile is cached.
 */
@Import(ProfileCacheTest.FakeTickerConfig.class)
class ProfileCacheTest extends MatchingServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String CITY = "testville";

    @LocalServerPort
    private int port;

    @Autowired
    private FakeTicker ticker;

    @Value("${app.profile-cache.window}")
    private Duration window;

    @BeforeEach
    void resetStubs() {
        StubUpstream.instance().reset();
        StubLlm.instance().reset();
    }

    @TestConfiguration
    static class FakeTickerConfig {
        @Bean
        FakeTicker ticker() {
            return new FakeTicker();
        }
    }

    static class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(0);

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @Test
    void aSecondRequestWithinTheWindowDoesNotAskForTheProfile() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        rankable(userId, "within-window-1", "Within Window Job");

        HttpResponse<String> first = topMatches(userId);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(1);
        assertThat(StubUpstream.instance().calls("/internal/users/" + userId)).isEqualTo(1);

        ticker.advance(window.minusSeconds(1));

        HttpResponse<String> second = topMatches(userId);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(1);
        assertThat(StubUpstream.instance().calls("/internal/users/" + userId)).isEqualTo(2);

        ticker.advance(Duration.ofSeconds(2));

        HttpResponse<String> third = topMatches(userId);
        assertThat(third.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(2);
        assertThat(StubUpstream.instance().calls("/internal/users/" + userId)).isEqualTo(3);
    }

    @Test
    void aProfileWithTooFewSkillsIsAskedForAgain() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("too-few-skills-1", "Too Few Skills Job", "java", "sql");
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\"],\"preferredCity\":\"testville\"}");

        HttpResponse<String> first = topMatches(userId);
        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(1);

        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
        StubLlm.instance().willScoreInPromptOrder(80);

        HttpResponse<String> second = topMatches(userId);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(2);
    }

    @Test
    void noProfileIsAskedForAgain() throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        knownUser(userId);
        stubPosting("no-profile-1", "No Profile Job", "java", "sql");
        StubUpstream.instance().refuse("/internal/profiles/" + userId, 404);

        HttpResponse<String> first = topMatches(userId);
        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(1);

        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
        StubLlm.instance().willScoreInPromptOrder(80);

        HttpResponse<String> second = topMatches(userId);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(StubUpstream.instance().calls("/internal/profiles/" + userId)).isEqualTo(2);
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

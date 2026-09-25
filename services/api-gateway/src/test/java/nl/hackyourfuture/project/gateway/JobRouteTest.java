package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Job search reaches an upstream of its own once {@code gateway.job-service-url} is set (Day 40, Track A). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobRouteTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final RecordingUpstream JOB_SERVICE = new RecordingUpstream();
    private static final TestKeys KEYS = new TestKeys();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
        registry.add("gateway.job-service-url", JOB_SERVICE::url);
        registry.add("gateway.jwks-url", KEYS::jwksUrl);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
        JOB_SERVICE.close();
        KEYS.close();
    }

    @BeforeEach
    void clear() {
        BACKEND.clear();
        JOB_SERVICE.clear();
    }

    @Test
    void everyJobPathReachesJobServiceWithTheVerifiedUserId() throws Exception {
        UUID caller = UUID.randomUUID();
        String cookie = KEYS.valid(caller);

        // The client claims to be someone else; the token's subject is what arrives.
        for (String path : new String[] {"/api/jobs?city=Amsterdam&page=2", "/api/jobs/filters", "/api/jobs/seed-0001"}) {
            assertThat(send(HttpRequest.newBuilder(at(path))
                    .header("Cookie", "access_token=" + cookie)
                    .header(Routes.USER_ID, UUID.randomUUID().toString())
                    .GET()).statusCode()).as(path).isEqualTo(200);
        }

        assertThat(JOB_SERVICE.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly(
                        "/api/jobs?city=Amsterdam&page=2",
                        "/api/jobs/filters",
                        "/api/jobs/seed-0001"
                );
        assertThat(JOB_SERVICE.received()).allSatisfy(request ->
                assertThat(request.headers().get(Routes.USER_ID)).containsExactly(caller.toString()));
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void topMatchesSavedJobsAndTheKeySetStayOnTheBackend() throws Exception {
        UUID caller = UUID.randomUUID();
        String cookie = KEYS.valid(caller);

        for (String path : new String[] {"/api/jobs/top-matches", "/api/saved-jobs", "/.well-known/jwks.json"}) {
            assertThat(send(HttpRequest.newBuilder(at(path))
                    .header("Cookie", "access_token=" + cookie)
                    .GET()).statusCode()).as(path).isEqualTo(200);
        }

        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly(
                        "/api/jobs/top-matches",
                        "/api/saved-jobs",
                        "/.well-known/jwks.json"
                );
        assertThat(JOB_SERVICE.received()).isEmpty();
    }

    private URI at(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

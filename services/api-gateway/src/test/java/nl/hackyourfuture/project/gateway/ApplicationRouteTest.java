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

/** Saved jobs reach an upstream of their own once {@code gateway.application-service-url} is set (Day 25, Track D). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationRouteTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final RecordingUpstream JOB_SERVICE = new RecordingUpstream();
    private static final RecordingUpstream MATCHING_SERVICE = new RecordingUpstream();
    private static final RecordingUpstream APPLICATION_SERVICE = new RecordingUpstream();
    private static final TestKeys KEYS = new TestKeys();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.identity-service-url", BACKEND::url);
        registry.add("gateway.job-service-url", JOB_SERVICE::url);
        registry.add("gateway.matching-service-url", MATCHING_SERVICE::url);
        registry.add("gateway.application-service-url", APPLICATION_SERVICE::url);
        registry.add("gateway.jwks-url", KEYS::jwksUrl);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
        JOB_SERVICE.close();
        MATCHING_SERVICE.close();
        APPLICATION_SERVICE.close();
        KEYS.close();
    }

    @BeforeEach
    void clear() {
        BACKEND.clear();
        JOB_SERVICE.clear();
        MATCHING_SERVICE.clear();
        APPLICATION_SERVICE.clear();
    }

    @Test
    void savedJobsReachApplicationServiceWithTheTokenAndTheVerifiedUserId() throws Exception {
        UUID caller = UUID.randomUUID();
        String cookie = KEYS.valid(caller);

        // The client claims to be someone else; the token's subject is what arrives. The cookie must
        // arrive too: the service verifies the token itself and does not read X-User-Id.
        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs?page=1&size=20"))
                .header("Cookie", "access_token=" + cookie)
                .header(Routes.USER_ID, UUID.randomUUID().toString())
                .GET()).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs"))
                .header("Cookie", "access_token=" + cookie)
                .header("Content-Type", "application/json")
                .header(Routes.USER_ID, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"postingId\":\"seed-0001\"}"))).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs/seed-0001"))
                .header("Cookie", "access_token=" + cookie)
                .header("Content-Type", "application/json")
                .header(Routes.USER_ID, UUID.randomUUID().toString())
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"state\":\"APPLIED\"}"))).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs/seed-0001"))
                .header("Cookie", "access_token=" + cookie)
                .header(Routes.USER_ID, UUID.randomUUID().toString())
                .DELETE()).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs/stats"))
                .header("Cookie", "access_token=" + cookie)
                .header(Routes.USER_ID, UUID.randomUUID().toString())
                .GET()).statusCode()).isEqualTo(200);

        assertThat(APPLICATION_SERVICE.received()).hasSize(5);
        assertThat(APPLICATION_SERVICE.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly(
                        "/api/saved-jobs?page=1&size=20",
                        "/api/saved-jobs",
                        "/api/saved-jobs/seed-0001",
                        "/api/saved-jobs/seed-0001",
                        "/api/saved-jobs/stats"
                );

        assertThat(APPLICATION_SERVICE.received()).extracting(RecordingUpstream.Received::method)
                .containsExactly("GET", "POST", "PATCH", "DELETE", "GET");

        assertThat(APPLICATION_SERVICE.received())
                .allSatisfy(request -> {
                    assertThat(request.headers().get("Cookie")).containsExactly("access_token=" + cookie);
                    assertThat(request.headers().get(Routes.USER_ID)).containsExactly(caller.toString());
                });

        assertThat(BACKEND.received()).isEmpty();
        assertThat(JOB_SERVICE.received()).isEmpty();
        assertThat(MATCHING_SERVICE.received()).isEmpty();
    }

    @Test
    void jobSearchTopMatchesAndTheKeySetDoNotReachIt() throws Exception {
        UUID caller = UUID.randomUUID();
        String cookie = KEYS.valid(caller);

        assertThat(send(HttpRequest.newBuilder(at("/api/jobs?city=Amsterdam"))
                .header("Cookie", "access_token=" + cookie)
                .GET()).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/jobs/top-matches"))
                .header("Cookie", "access_token=" + cookie)
                .GET()).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/.well-known/jwks.json"))
                .header("Cookie", "access_token=" + cookie)
                .GET()).statusCode()).isEqualTo(200);

        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobsx"))
                .header("Cookie", "access_token=" + cookie)
                .GET()).statusCode()).isEqualTo(200);

        assertThat(JOB_SERVICE.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly("/api/jobs?city=Amsterdam");

        assertThat(MATCHING_SERVICE.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly("/api/jobs/top-matches");

        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly(
                        "/.well-known/jwks.json",
                        "/api/saved-jobsx"
                );

        assertThat(APPLICATION_SERVICE.received()).isEmpty();
    }

    @Test
    void savedJobsWithoutATokenAreRefusedAndReachNothing() throws Exception {
        assertThat(send(HttpRequest.newBuilder(at("/api/saved-jobs"))
                .GET()).statusCode()).isEqualTo(401);

        assertThat(BACKEND.received()).isEmpty();
        assertThat(JOB_SERVICE.received()).isEmpty();
        assertThat(MATCHING_SERVICE.received()).isEmpty();
        assertThat(APPLICATION_SERVICE.received()).isEmpty();
    }

    private URI at(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

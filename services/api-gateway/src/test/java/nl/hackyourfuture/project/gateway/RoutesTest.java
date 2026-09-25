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

/** The API and the JWKS reach the backend, unchanged; nothing else does (Day 15, Track A). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutesTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final TestKeys KEYS = new TestKeys();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
        registry.add("gateway.jwks-url", KEYS::jwksUrl);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
        KEYS.close();
    }

    @BeforeEach
    void clear() {
        BACKEND.clear();
    }

    @Test
    void anApiRequestReachesTheBackendWithItsQueryAndComesBack() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(at("/api/jobs?city=Amsterdam&page=2")).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(RecordingUpstream.BODY);
        assertThat(BACKEND.received()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.pathAndQuery()).isEqualTo("/api/jobs?city=Amsterdam&page=2");
        });
    }

    @Test
    void aPostReachesTheBackendWithItsBody() throws Exception {
        String login = "{\"email\":\"someone@example.test\",\"password\":\"Password123!\"}";

        send(HttpRequest.newBuilder(at("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(login)));

        assertThat(BACKEND.received()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.body()).isEqualTo(login);
        });
    }

    @Test
    void theKeySetReachesTheBackend() throws Exception {
        send(HttpRequest.newBuilder(at("/.well-known/jwks.json")).GET());

        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly("/.well-known/jwks.json");
    }

    // What the gateway answers instead is Track B's: 401 without a token, as the backend does.
    @Test
    void theBackendsActuatorAndOtherPathsAreNotRouted() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/prometheus", "/login", "/"}) {
            send(HttpRequest.newBuilder(at(path)).GET());
        }

        assertThat(BACKEND.received()).isEmpty();
    }

    // With no job-service URL set, every job path reaches the backend, and that holds once job paths
    // have a route of their own until Day 17 sets one. top-matches is matching's, and needs a token.
    @Test
    void everyJobPathReachesTheBackendUnchanged() throws Exception {
        assertThat(send(HttpRequest.newBuilder(at("/api/jobs?city=Amsterdam&page=2")).GET()).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(at("/api/jobs/filters")).GET()).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(at("/api/jobs/seed-0001")).GET()).statusCode()).isEqualTo(200);

        String cookie = KEYS.valid(UUID.randomUUID());
        assertThat(send(HttpRequest.newBuilder(at("/api/jobs/top-matches"))
                .header("Cookie", "access_token=" + cookie)
                .GET()).statusCode()).isEqualTo(200);

        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly(
                        "/api/jobs?city=Amsterdam&page=2",
                        "/api/jobs/filters",
                        "/api/jobs/seed-0001",
                        "/api/jobs/top-matches"
                );
    }

    private URI at(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

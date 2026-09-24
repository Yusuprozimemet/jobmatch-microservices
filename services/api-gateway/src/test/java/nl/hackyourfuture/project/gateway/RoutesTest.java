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

import static org.assertj.core.api.Assertions.assertThat;

/** The API and the JWKS reach the backend, unchanged; nothing else does (Day 15, Track A). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutesTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
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

    private URI at(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

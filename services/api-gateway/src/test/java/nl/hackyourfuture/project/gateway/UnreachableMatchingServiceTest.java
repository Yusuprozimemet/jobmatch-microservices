package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
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

/**
 * A top-matches request whose service is down is the gateway's 502, while the backend stays up
 * (Day 21, Track D). Port 1 is closed on any test machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.matching-service-url=http://127.0.0.1:1")
class UnreachableMatchingServiceTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final TestKeys KEYS = new TestKeys();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("gateway.identity-service-url", BACKEND::url);
        registry.add("gateway.jwks-url", KEYS::jwksUrl);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
        KEYS.close();
    }

    @Test
    void topMatchesWhoseServiceIsDownIsABadGatewayAndTheBackendStillAnswers() throws Exception {
        String cookie = KEYS.valid(UUID.randomUUID());

        HttpResponse<String> topMatchesResponse = send(HttpRequest.newBuilder(at("/api/jobs/top-matches"))
                .header("Cookie", "access_token=" + cookie)
                .GET());
        assertThat(topMatchesResponse.statusCode()).isEqualTo(502);

        HttpResponse<String> keySetResponse = send(HttpRequest.newBuilder(at("/.well-known/jwks.json")).GET());
        assertThat(keySetResponse.statusCode()).isEqualTo(200);

        // The key set reached the backend.
        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery)
                .containsExactly("/.well-known/jwks.json");
    }

    private URI at(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

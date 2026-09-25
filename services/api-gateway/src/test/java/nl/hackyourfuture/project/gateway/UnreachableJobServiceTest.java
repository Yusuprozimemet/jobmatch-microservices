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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job path whose service is down is the gateway's 502, while the backend stays up (Day 40, Track A).
 * Port 1 is closed on any test machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.job-service-url=http://127.0.0.1:1")
class UnreachableJobServiceTest {

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

    @Test
    void aJobPathWhoseServiceIsDownIsABadGatewayAndTheBackendStillAnswers() throws Exception {
        HttpResponse<String> jobResponse = send(HttpRequest.newBuilder(at("/api/jobs")).GET());
        assertThat(jobResponse.statusCode()).isEqualTo(502);

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

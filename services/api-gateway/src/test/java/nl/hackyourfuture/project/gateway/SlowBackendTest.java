package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend that does not answer within the read timeout is the gateway's 504, answered when the
 * timeout runs out rather than when the backend finally replies (Day 15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=1s")
class SlowBackendTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();

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
    void aBackendSlowerThanTheReadTimeoutIsAGatewayTimeout() throws Exception {
        BACKEND.answerAfter(Duration.ofSeconds(4));
        long started = System.nanoTime();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("answered at the timeout").isLessThan(Duration.ofSeconds(3));
    }
}

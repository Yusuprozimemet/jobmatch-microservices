package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
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
 * The gateway's own health and metrics, on a management port compose does not publish (Day 38),
 * for the probes Day 34 adds and the metrics Day 37 reads. Nothing here needs a token: the port
 * is reachable only from inside the network. {@code SecurityTest} checks that the public port
 * serves none of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementPortTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
    }

    @Test
    void healthAnswersWithoutAToken() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    // What the backend's gateway harness waits for, and Day 34's readiness probe.
    @Test
    void readinessAnswersOnceStarted() throws Exception {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void aRoutedCallIsCountedForPrometheus() throws Exception {
        assertThat(get(port, "/api/docs/openapi.yaml").statusCode()).isEqualTo(200);

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        // Tagged with the route's pattern, not the path: one series for everything under /api. A
        // path no other route will take, so a route of their own for job paths leaves this as it is.
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body().lines().filter(line -> line.startsWith("http_server_requests_seconds_count")))
                .anySatisfy(line -> assertThat(line).contains("uri=\"/api/**\"").contains("status=\"200\""));
    }

    // Only health and prometheus are exposed, and the chain that permits actuator matches only
    // what is exposed: the endpoint that would print the configuration is refused.
    @Test
    void nothingElseIsServed() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

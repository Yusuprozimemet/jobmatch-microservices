package nl.hackyourfuture.project.jobservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job-service's health and metrics, on a management port compose does not publish.
 * Nothing here needs a token: the port is reachable only from inside the network.
 */
class ManagementPortTest extends JobServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void healthAnswersWithoutAToken() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessAnswersOnceStarted() throws Exception {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void aRequestIsCountedForPrometheus() throws Exception {
        assertThat(get(port, "/api/jobs").statusCode()).isNotEqualTo(401);

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertThat(metrics.statusCode()).isEqualTo(200);
        // Request is counted, route pattern may be unknown due to mocked database.
        assertThat(metrics.body()).contains("http_server_requests_seconds_count");
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

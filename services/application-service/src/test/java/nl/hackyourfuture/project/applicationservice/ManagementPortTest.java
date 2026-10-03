package nl.hackyourfuture.project.applicationservice;

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
 * application-service's health and metrics, on a management port compose does not publish.
 * Nothing here needs a token: the port is reachable only from inside the network.
 */
class ManagementPortTest extends ApplicationServiceTest {

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
        // No user token is read yet (Track A1b), so the application port answers 401.
        assertThat(get(port, "/api/saved-jobs").statusCode()).isEqualTo(401);

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertThat(metrics.statusCode()).isEqualTo(200);
        // The 401: the management port's own requests all answer 200.
        assertThat(metrics.body()).containsPattern("http_server_requests_seconds_count\\{[^}]*status=\"401\"");
    }


    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

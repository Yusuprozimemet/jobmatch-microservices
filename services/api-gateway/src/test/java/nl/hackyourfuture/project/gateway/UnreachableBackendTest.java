package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend nothing answers for is the gateway's 502, not a 500 that says the gateway broke
 * (Day 15). Port 1 is closed on any test machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.backend-url=http://127.0.0.1:1")
class UnreachableBackendTest {

    @LocalServerPort
    private int port;

    @Test
    void aRouteWhoseBackendIsDownIsABadGateway() throws Exception {
        for (String route : new String[] {"GET /api/jobs", "POST /api/auth/login"}) {
            String[] methodAndPath = route.split(" ");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + methodAndPath[1]))
                            .method(methodAndPath[0], HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(route).isEqualTo(502);
        }
    }
}

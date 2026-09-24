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

/** No other origin may call the API from a browser; the frontend's own requests still pass (Day 15). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossOriginTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String ANOTHER_ORIGIN = "https://evil.example";

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
    void aPreflightFromAnotherOriginIsRefusedHere() throws Exception {
        for (String[] route : new String[][] {{"GET", "/api/jobs"}, {"POST", "/api/auth/login"}, {"GET", "/api/profile"}}) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(at(route[1]))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", ANOTHER_ORIGIN)
                    .header("Access-Control-Request-Method", route[0]));

            assertThat(response.statusCode()).as(route[1]).isEqualTo(403);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).as(route[1]).isEmpty();
        }
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void anAllowOriginSetBehindTheGatewayNeverLeavesIt() throws Exception {
        BACKEND.answerWith("Access-Control-Allow-Origin", "*");
        BACKEND.answerWith("Access-Control-Allow-Credentials", "true");

        HttpResponse<String> response = send(HttpRequest.newBuilder(at("/api/jobs")).header("Origin", ANOTHER_ORIGIN).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().map().keySet()).noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
    }

    // Through the Next.js proxy the browser's Origin arrives with the gateway's host: not cross-origin.
    @Test
    void theFrontendsOwnRequestThroughItsProxyIsServed() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(at("/api/auth/login"))
                .header("Origin", "http://localhost:3000")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(BACKEND.received()).hasSize(1);
    }

    private URI at(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

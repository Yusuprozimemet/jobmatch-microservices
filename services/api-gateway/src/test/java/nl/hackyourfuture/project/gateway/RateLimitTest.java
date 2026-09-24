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
 * Ten credential requests a minute per client; the eleventh is answered 429 here and never reaches
 * the backend (Day 15). A client connecting directly is its address: what it writes in
 * {@code X-Forwarded-For} is not believed. One test, because this context has one client address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.trusted-proxies=")
class RateLimitTest {

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
    void theEleventhLoginInAMinuteIsRefusedWhateverTheClientClaimsToBe() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(post(port, "/api/auth/login", "10.0.0." + i).statusCode()).as("login %d", i).isEqualTo(200);
        }

        assertThat(post(port, "/api/auth/login", "10.0.0.11").statusCode()).isEqualTo(429);
        assertThat(BACKEND.received()).as("the eleventh never reached the backend").hasSize(10);

        for (int i = 1; i <= 20; i++) {
            assertThat(post(port, "/api/auth/refresh", null).statusCode()).as("refresh %d", i).isEqualTo(200);
        }
    }

    static HttpResponse<String> post(int port, String path, String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (forwardedFor != null) {
            request.header(RateLimit.FORWARDED_FOR, forwardedFor);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

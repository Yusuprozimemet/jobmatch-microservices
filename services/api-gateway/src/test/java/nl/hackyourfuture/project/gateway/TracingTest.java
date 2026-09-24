package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One trace spans the gateway and what is behind it: the backend receives a {@code traceparent}
 * whose trace id is the one the gateway logs for the request, which is the correlation id (Day 15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTracing
class TracingTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String TRACEPARENT = "traceparent";

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
    void theBackendGetsTheTraceTheGatewayLogs(CapturedOutput output) throws Exception {
        get("/api/jobs?page=3", null);

        String traceparent = BACKEND.received().getFirst().headers().getFirst(TRACEPARENT);
        assertThat(traceparent).as("W3C, sampled").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        String traceId = traceparent.split("-")[1];
        // The line is written once the request is done, which can be after the client has the answer.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(output.getOut().lines().filter(line -> line.contains("GET /api/jobs 200")))
                        .singleElement().asString().contains(traceId));
    }

    @Test
    void aTraceTheCallerStartedIsContinuedNotReplaced() throws Exception {
        String callersTrace = "4bf92f3577b34da6a3ce929d0e0e4736";

        get("/api/jobs", "00-" + callersTrace + "-00f067aa0ba902b7-01");

        String traceparent = BACKEND.received().getFirst().headers().getFirst(TRACEPARENT);
        assertThat(traceparent).startsWith("00-" + callersTrace + "-").doesNotContain("00f067aa0ba902b7");
    }

    private void get(String path, String traceparent) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (traceparent != null) {
            request.header(TRACEPARENT, traceparent);
        }
        CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

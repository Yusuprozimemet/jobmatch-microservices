package nl.hackyourfuture.project.applicationservice;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The internal clients serve, and are measured and traced like any outgoing call (Day 38): the
 * {@code /api/saved-jobs} request answers 200 through its posting lookup client, and each client
 * call is an {@code http_client_requests} line with its route and status, and a client span in
 * the trace of the request that made it. An ancestor, not the parent: the parent is Spring
 * Security's {@code secured request} span.
 *
 * <p>Moved from the monolith's InternalCallsObservedIT on Day 25.
 */
@AutoConfigureTracing
@Import(EndedSpans.Config.class)
class InternalCallsObservedTest extends ApplicationServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Each internal route, and the {@code /api} request whose client calls it. */
    private static final Map<String, String> CALLED_BY = Map.of(
            "/internal/postings/batch", "/api/saved-jobs");

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int port;

    @Autowired
    private EndedSpans spans;

    private SavedJobsCalls calls;

    @BeforeEach
    void setUp() throws Exception {
        spans.clear();
        StubUpstream.instance().reset();
        SavedJobsCalls.deleteAll();
        calls = new SavedJobsCalls(port);
    }

    @Test
    void everyInternalCallIsCountedWithItsRouteAndStatus() throws Exception {
        askSavedJobs("observed-metric");

        String metrics = get(managementPort, "/actuator/prometheus").body();
        List<String> counts = metrics.lines()
                .filter(line -> line.startsWith("http_client_requests_seconds_count"))
                .toList();
        CALLED_BY.keySet().forEach(route -> assertThat(counts)
                .as(route)
                .anyMatch(line -> line.contains("uri=\"" + route + "\"") && line.contains("status=\"2")));
    }

    @Test
    void everyInternalCallIsAClientSpanInsideItsApiRequestsTrace() throws Exception {
        askSavedJobs("observed-span");

        List<SpanData> ended = spans.all();
        CALLED_BY.forEach((route, apiRequest) -> {
            SpanData client = ended.stream()
                    .filter(span -> span.getKind() == SpanKind.CLIENT)
                    .filter(span -> route.equals(span.getAttributes().get(AttributeKey.stringKey("uri"))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no client span for " + route));
            assertThat(serverAncestor(client, ended))
                    .as(route + "'s server ancestor")
                    .hasValueSatisfying(server -> assertThat(server.getName()).endsWith(apiRequest));
        });
    }

    /** Saved jobs endpoint, which calls the posting lookup client. */
    private void askSavedJobs(String postingId) throws Exception {
        UUID user = UUID.randomUUID();
        SavedJobsCalls.save(user, postingId);
        StubUpstream.instance().answer("/internal/postings/batch", 200, "{\"" + postingId + "\":{\"title\":\"Test Posting\"}}");

        var response = calls.savedJobs(user);
        assertThat(response.status()).isEqualTo(200);
    }

    /** The nearest server span above {@code span} in its trace, walking parent ids. */
    private static Optional<SpanData> serverAncestor(SpanData span, List<SpanData> ended) {
        Optional<SpanData> parent = parentOf(span, ended);
        while (parent.isPresent() && parent.get().getKind() != SpanKind.SERVER) {
            parent = parentOf(parent.get(), ended);
        }
        return parent;
    }

    private static Optional<SpanData> parentOf(SpanData span, List<SpanData> ended) {
        return ended.stream()
                .filter(other -> other.getTraceId().equals(span.getTraceId()))
                .filter(other -> other.getSpanId().equals(span.getParentSpanId()))
                .findFirst();
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

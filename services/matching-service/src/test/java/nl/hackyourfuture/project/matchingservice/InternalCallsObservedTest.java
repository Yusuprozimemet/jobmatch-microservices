package nl.hackyourfuture.project.matchingservice;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The internal clients serve, and are measured and traced like the LLM call (Day 38): each
 * {@code /api} request below answers 200 through its client, and each client call is an
 * {@code http_client_requests} line with its route and status, and a client span in the trace of
 * the request that made it. An ancestor, not the parent: the parent is Spring Security's
 * {@code secured request} span.
 *
 * <p>Moved from the monolith on Day 21: the top-matches calls. The saved-jobs call stays there, in
 * {@code InternalCallsObservedIT}.
 */
@AutoConfigureTracing
@Import(EndedSpans.Config.class)
class InternalCallsObservedTest extends MatchingServiceTest {

    /** Each internal route, and the {@code /api} request whose client calls it. */
    private static final Map<String, String> CALLED_BY = Map.of(
            "/internal/postings/shortlist", "/api/jobs/top-matches",
            "/internal/profiles/{userId}", "/api/jobs/top-matches",
            "/internal/users/{id}", "/api/jobs/top-matches");

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int port;

    @Autowired
    private EndedSpans spans;

    private TopMatchesRequest request;

    @BeforeEach
    void forgetEarlierSpans() {
        spans.clear();
        StubUpstream.instance().reset();
        StubLlm.instance().reset();
        request = new TopMatchesRequest(port, managementPort);
    }

    @Test
    void everyInternalCallIsCountedWithItsRouteAndStatus() throws IOException, InterruptedException {
        askTopMatches("observed-metric");

        String metrics = request.prometheus();
        List<String> counts = metrics.lines()
                .filter(line -> line.startsWith("http_client_requests_seconds_count"))
                .toList();
        CALLED_BY.keySet().forEach(route -> assertThat(counts)
                .as(route)
                .anyMatch(line -> line.contains("uri=\"" + route + "\"") && line.contains("status=\"2")));
    }

    @Test
    void everyInternalCallIsAClientSpanInsideItsApiRequestsTrace() throws IOException, InterruptedException {
        askTopMatches("observed-span");

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

    private void askTopMatches(String postingId) throws IOException, InterruptedException {
        assertThat(request.send(postingId).statusCode()).isEqualTo(200);
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
}

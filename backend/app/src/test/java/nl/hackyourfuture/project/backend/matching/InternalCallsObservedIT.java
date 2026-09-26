package nl.hackyourfuture.project.backend.matching;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;

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
 * <p>Annotated as {@link LlmCallObservedIT} is, so it shares that test's context.
 */
@AutoConfigureTracing
@Import(LlmCallObservedIT.Spans.class)
class InternalCallsObservedIT extends MatchingTest {

    /** Each internal route, and the {@code /api} request whose client calls it. */
    private static final Map<String, String> CALLED_BY = Map.of(
            "/internal/postings/batch", "/api/saved-jobs",
            "/internal/postings/shortlist", "/api/jobs/top-matches",
            "/internal/saved-counts", "/api/jobs");

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private LlmCallObservedIT.EndedSpans spans;

    @BeforeEach
    void forgetEarlierSpans() {
        spans.clear();
    }

    @Test
    void everyInternalCallIsCountedWithItsRouteAndStatus() {
        askEveryCaller("observed-metric");

        List<String> counts = ApiClient.onPort(managementPort).get("/actuator/prometheus").body().lines()
                .filter(line -> line.startsWith("http_client_requests_seconds_count"))
                .toList();
        CALLED_BY.keySet().forEach(route -> assertThat(counts)
                .as(route)
                .anyMatch(line -> line.contains("uri=\"" + route + "\"") && line.contains("status=\"200\"")));
    }

    @Test
    void everyInternalCallIsAClientSpanInsideItsApiRequestsTrace() {
        askEveryCaller("observed-span");

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

    /** Saved jobs, top matches and job search, each through its client, each answering 200. */
    private void askEveryCaller(String prefix) {
        TestUser saver = aUser().create();
        posting(prefix + "-saved", "Observed Saved Job", "java");
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", saver.id())
                .param("postingId", prefix + "-saved")
                .update();
        assertThat(authenticatedAs(saver).get("/api/saved-jobs").status()).isEqualTo(200);

        TestUser matcher = userWithProfile();
        posting(prefix + "-match", "Observed Engineer", "java", "sql");
        model().willScoreInPromptOrder(80);
        assertThat(authenticatedAs(matcher).get("/api/jobs/top-matches").status()).isEqualTo(200);

        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);
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

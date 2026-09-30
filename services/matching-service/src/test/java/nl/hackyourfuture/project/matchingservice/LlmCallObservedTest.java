package nl.hackyourfuture.project.matchingservice;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call to the language model is measured and traced like any other outgoing call (Day 38).
 * Built with {@code RestClient.builder()} it was neither: no {@code http_client_requests} line
 * and no client span, so the slowest dependency of {@code /api/jobs/top-matches} was invisible.
 *
 * <p>With the real tracer, which {@code @SpringBootTest} otherwise swaps for a no-op one, and a
 * span processor that keeps what ends, so no collector is needed.
 *
 * <p>Moved from the monolith on Day 21.
 */
@AutoConfigureTracing
@Import(EndedSpans.Config.class)
class LlmCallObservedTest extends MatchingServiceTest {

    private static final String LLM_URI = "uri=\"/chat/completions\"";

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
    void theCallIsCountedForPrometheus() throws IOException, InterruptedException {
        askForTopMatches("llm-metric-1");

        String metrics = request.prometheus();
        assertThat(metrics.lines()
                .filter(line -> line.startsWith("http_client_requests_seconds_count") && line.contains(LLM_URI)))
                .isNotEmpty();
    }

    @Test
    void theCallIsAClientSpan() throws IOException, InterruptedException {
        askForTopMatches("llm-span-1");

        assertThat(spans.all())
                .filteredOn(span -> span.getKind() == SpanKind.CLIENT)
                .extracting(span -> span.getAttributes().get(AttributeKey.stringKey("uri")))
                .contains("/chat/completions");
    }

    private void askForTopMatches(String postingId) throws IOException, InterruptedException {
        assertThat(request.send(postingId).statusCode()).isEqualTo(200);
        assertThat(StubLlm.instance().callCount()).isEqualTo(1);
    }
}

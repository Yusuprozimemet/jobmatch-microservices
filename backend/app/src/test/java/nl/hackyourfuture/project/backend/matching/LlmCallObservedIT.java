package nl.hackyourfuture.project.backend.matching;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call to the language model is measured and traced like any other outgoing call (Day 38).
 * Built with {@code RestClient.builder()} it was neither: no {@code http_client_requests} line
 * and no client span, so the slowest dependency of {@code /api/jobs/top-matches} was invisible.
 *
 * <p>With the real tracer, which {@code @SpringBootTest} otherwise swaps for a no-op one, and a
 * span processor that keeps what ends, so no collector is needed.
 */
@AutoConfigureTracing
@Import(LlmCallObservedIT.Spans.class)
class LlmCallObservedIT extends MatchingTest {

    private static final String LLM_URI = "uri=\"/chat/completions\"";

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private EndedSpans spans;

    @BeforeEach
    void forgetEarlierSpans() {
        spans.clear();
    }

    @Test
    void theCallIsCountedForPrometheus() {
        askForTopMatches("llm-metric-1");

        assertThat(ApiClient.onPort(managementPort).get("/actuator/prometheus").body().lines()
                .filter(line -> line.startsWith("http_client_requests_seconds_count") && line.contains(LLM_URI)))
                .isNotEmpty();
    }

    @Test
    void theCallIsAClientSpan() {
        askForTopMatches("llm-span-1");

        assertThat(spans.all())
                .filteredOn(span -> span.getKind() == SpanKind.CLIENT)
                .extracting(span -> span.getAttributes().get(AttributeKey.stringKey("uri")))
                .contains("/chat/completions");
    }

    private void askForTopMatches(String postingId) {
        var user = userWithProfile();
        posting(postingId, "Observed Engineer", "java", "sql");
        model().willScoreInPromptOrder(80);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(model().callCount()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Spans {

        // Spring Boot adds every SpanProcessor bean to the tracer provider it builds.
        @Bean
        EndedSpans endedSpans() {
            return new EndedSpans();
        }
    }

    /** Keeps every span that ends, in memory, for the test to read. */
    static final class EndedSpans implements SpanProcessor {

        private final List<SpanData> ended = new CopyOnWriteArrayList<>();

        List<SpanData> all() {
            return List.copyOf(ended);
        }

        void clear() {
            ended.clear();
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            ended.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}

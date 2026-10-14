package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs as JSON, with whatever is in MDC flattened into the object.
 *
 * <p>That flattening is the half of trace correlation the application owns. The other half is
 * the OpenTelemetry agent, which puts {@code trace_id} and {@code span_id} into MDC on every
 * request — it runs as a {@code -javaagent} in the image and not in this JVM, so the keys are
 * set here by hand. What is pinned is that a key in MDC reaches the log line: once the agent
 * supplies the real ones, they arrive the same way.
 *
 * <p>Whether the agent is actually attached and emitting spans is checked by hand against the
 * compose stack, per the day's spec. This test is what fails if someone removes the structured
 * logging configuration.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.structured.format.console=logstash")
class ObservabilityLoggingIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityLoggingIT.class);

    @Test
    void writesEachLogLineAsJson(CapturedOutput output) {
        log.info("a message from the test");

        JsonNode line = lastJsonLineContaining(output, "a message from the test");

        assertThat(line.get("message").asString()).isEqualTo("a message from the test");
        assertThat(line.get("level").asString()).isEqualTo("INFO");
        assertThat(line.get("logger_name").asString()).contains("ObservabilityLoggingIT");
        assertThat(line.get("@timestamp").asString()).isNotBlank();
    }

    // Set here by hand: in a running container the agent puts these in MDC itself.
    @Test
    void mdcKeysAppearAsFieldsRatherThanInTheMessage(CapturedOutput output) {
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        try {
            log.info("handling a request");
        } finally {
            MDC.clear();
        }

        JsonNode line = lastJsonLineContaining(output, "handling a request");

        // A field a collector can index and filter on, not text inside the message.
        assertThat(line.get("trace_id").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(line.get("span_id").asString()).isEqualTo("00f067aa0ba902b7");
        assertThat(line.get("message").asString()).isEqualTo("handling a request");
    }

    // Two lines written under the same trace id carry it identically - the property that makes
    // "show me everything that happened in this request" a search rather than a guess.
    @Test
    void everyLineInTheSameTraceCarriesTheSameIdentifier(CapturedOutput output) {
        MDC.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        try {
            log.info("first thing");
            log.info("second thing");
        } finally {
            MDC.clear();
        }

        assertThat(lastJsonLineContaining(output, "first thing").get("trace_id").asString())
                .isEqualTo(lastJsonLineContaining(output, "second thing").get("trace_id").asString())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    /**
     * The half the agent was supposed to provide: a line logged while serving a request
     * carries the identifier of the trace that request belongs to.
     *
     * <p>Driven over HTTP rather than by setting MDC, because the point is that the server
     * populates it. {@code POST /api/auth/forgot-password} for a Google-only account logs
     * synchronously inside the request, which makes it the one request in this application
     * that writes a log line of its own.
     */
    @Test
    void aLineLoggedWhileServingARequestCarriesTheTraceId(CapturedOutput output) {
        TestUser googleOnly = aUser().googleAccount("google-" + System.nanoTime()).create();

        anonymous().post("/api/auth/forgot-password", Map.of("email", googleOnly.email()));

        JsonNode line = lastJsonLineContaining(output, "Skipping password reset email");
        assertThat(line.get("traceId")).as("traceId on a request-scoped log line").isNotNull();
        assertThat(line.get("traceId").asString()).hasSize(32);
        assertThat(line.get("spanId").asString()).hasSize(16);
    }

    private static JsonNode lastJsonLineContaining(CapturedOutput output, String message) {
        String[] lines = output.getAll().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.contains(message)) {
                return JsonMapper.builder().build().readTree(line);
            }
        }
        throw new AssertionError("No JSON log line containing '" + message + "' in:\n" + output.getAll());
    }
}

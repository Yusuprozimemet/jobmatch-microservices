package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend logs under the trace the gateway forwarded, so one trace id finds a request on both
 * sides (Day 15). With span export off, as it is by default: Spring Boot sets up propagation only
 * while export is on, and this is the case that would otherwise log a trace of the backend's own.
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTracing
@TestPropertySource(properties = {"logging.structured.format.console=logstash", "management.tracing.export.enabled=false"})
class TraceContinuedIT extends IntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void aRequestIsLoggedUnderTheTraceItArrivedWith(CapturedOutput output) {
        TestUser user = aUser().create();
        ApiClient browser = authenticatedAs(user).withHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        assertThat(browser.patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "Another-Password-1")).status()).isEqualTo(200);

        assertThat(jsonLineContaining(output, "Password successfully updated").get("traceId").asString()).isEqualTo(TRACE_ID);
    }

    private static JsonNode jsonLineContaining(CapturedOutput output, String message) {
        return output.getAll().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{") && line.contains(message))
                .reduce((first, second) -> second)
                .map(line -> JsonMapper.builder().build().readTree(line))
                .orElseThrow(() -> new AssertionError("No JSON log line containing '" + message + "'"));
    }
}

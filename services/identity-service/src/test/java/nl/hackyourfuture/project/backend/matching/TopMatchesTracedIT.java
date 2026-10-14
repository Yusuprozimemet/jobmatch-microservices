package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Gateway;
import nl.hackyourfuture.project.backend.support.JobService;
import nl.hackyourfuture.project.backend.support.MatchingService;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace id follows a top-matches request to both of its downstream calls: the model, and
 * job-service's shortlist route (Day 21). Read from what each side received, not from this
 * JVM's spans, so it holds wherever matching runs. Through the gateway with
 * {@code -Dharness.gateway=true}, which continues the trace (Day 15).
 *
 * <p>And one request is one trace across every hop (Day 24): the gateway, matching-service,
 * identity's existence and profile routes, and job-service's shortlist. The client sends no
 * {@code traceparent}, so the trace is the gateway's own, not one it merely forwards. Each hop is
 * read from its request log line, prefixed with the trace id: identity is this JVM, the rest are
 * containers.
 *
 * <p>With span export off, as in production: then the W3C propagator {@code TracingConfig}
 * declares is what continues the trace (see {@code TraceContinuedIT}).
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTracing
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "logging.structured.format.console=logstash",
        "logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG"
})
class TopMatchesTracedIT extends MatchingTest {

    private static final String SHORTLIST = "/internal/postings/shortlist";

    @Test
    void oneTraceReachesTheModelAndJobServicesShortlist() throws InterruptedException {
        TestUser user = userWithProfile();
        posting("traced-1", "Traced Engineer", "java", "sql");
        model().willScoreInPromptOrder(80);
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String parentSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ApiResponse response = authenticatedAs(user)
                .withHeader("traceparent", "00-" + traceId + "-" + parentSpanId + "-01")
                .get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(model().traceparents()).as("traceparent the model received")
                .singleElement().asString().contains(traceId);
        assertThat(shortlistLogLinesUnder(traceId)).as("job-service's " + SHORTLIST + " under trace " + traceId
                + "; its lines for that route: " + shortlistLogLines()).isNotEmpty();
    }

    @Test
    void oneRequestIsOneTraceAcrossEveryHop(CapturedOutput output) throws InterruptedException {
        TestUser user = userWithProfile();
        posting("four-hop-1", "Four Hop Engineer", "java", "sql");
        model().willScoreInPromptOrder(80);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);

        // The trace is whatever identity's existence route arrived under; every other hop must match it.
        JsonNode existenceLine = jsonLineContaining(output, "/internal/users/" + user.id());
        String traceId = existenceLine.path("traceId").asString();
        assertThat(traceId).as("identity's existence line: " + existenceLine).isNotBlank();

        JsonNode profileLine = jsonLineContaining(output, "/internal/profiles/" + user.id());
        assertThat(profileLine.path("traceId").asString())
                .as("identity's profile line: " + profileLine + "; existence line: " + existenceLine)
                .isEqualTo(traceId);

        List<String> matchingLogs = linesUnder(MatchingService::logs, traceId, "/api/jobs/top-matches");
        assertThat(matchingLogs).as("matching-service's /api/jobs/top-matches under trace " + traceId
                + "; its top-matches lines: " + topMatchesLinesFromMatching()).isNotEmpty();

        List<String> jobLogs = shortlistLogLinesUnder(traceId);
        assertThat(jobLogs).as("job-service's " + SHORTLIST + " under trace " + traceId
                + "; its lines for that route: " + shortlistLogLines()).isNotEmpty();

        // Without the gateway the request goes straight to matching-service: no gateway hop to check.
        if (Gateway.ON) {
            List<String> gatewayLogs = linesUnder(Gateway::logs, traceId, "/api/jobs/top-matches");
            assertThat(gatewayLogs).as("gateway's /api/jobs/top-matches under trace " + traceId
                    + "; its top-matches lines: " + topMatchesLinesFromGateway()).isNotEmpty();
        }
    }

    /** Log lines from logs() containing path and traceId; poll up to 5 s. */
    private static List<String> linesUnder(Supplier<String> logs, String traceId, String path)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = logs.get().lines().filter(line -> line.contains(traceId) && line.contains(path)).toList();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            found = logs.get().lines().filter(line -> line.contains(traceId) && line.contains(path)).toList();
        }
        return found;
    }

    /** job-service logs a request as it arrives; the line can lag the response, so poll 5 s. */
    private static List<String> shortlistLogLinesUnder(String traceId) throws InterruptedException {
        return linesUnder(JobService::logs, traceId, SHORTLIST);
    }

    private static List<String> shortlistLogLines() {
        return JobService.logs().lines().filter(line -> line.contains(SHORTLIST)).toList();
    }

    private static List<String> topMatchesLinesFromMatching() {
        return MatchingService.logs().lines().filter(line -> line.contains("/api/jobs/top-matches")).toList();
    }

    private static List<String> topMatchesLinesFromGateway() {
        return Gateway.logs().lines().filter(line -> line.contains("/api/jobs/top-matches")).toList();
    }

    private static JsonNode jsonLineContaining(CapturedOutput output, String message) {
        return output.getAll().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{") && line.contains(message))
                .findFirst()
                .map(line -> JsonMapper.builder().build().readTree(line))
                .orElseThrow(() -> new AssertionError("No JSON log line containing '" + message + "'"));
    }
}

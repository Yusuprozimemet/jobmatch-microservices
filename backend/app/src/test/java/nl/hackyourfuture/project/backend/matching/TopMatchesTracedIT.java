package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.JobService;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace id follows a top-matches request to both of its downstream calls: the model, and
 * job-service's shortlist route (Day 21). Read from what each side received, not from this
 * JVM's spans, so it holds wherever matching runs. Through the gateway with
 * {@code -Dharness.gateway=true}, which continues the trace (Day 15).
 *
 * <p>With span export off, as in production: then the W3C propagator {@code TracingConfig}
 * declares is what continues the trace (see {@code TraceContinuedIT}).
 */
@AutoConfigureTracing
@TestPropertySource(properties = "management.tracing.export.enabled=false")
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

    /** job-service logs a request as it arrives; the line can lag the response, so poll 5 s. */
    private static List<String> shortlistLogLinesUnder(String traceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = shortlistLogLines().stream().filter(line -> line.contains(traceId)).toList();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            found = shortlistLogLines().stream().filter(line -> line.contains(traceId)).toList();
        }
        return found;
    }

    private static List<String> shortlistLogLines() {
        return JobService.logs().lines().filter(line -> line.contains(SHORTLIST)).toList();
    }
}

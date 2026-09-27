package nl.hackyourfuture.project.backend.observability;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.EndedSpans;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * job-service is measured on its own port, and a trace crosses into it (Day 17, Track E2;
 * Day 40's hand-off). It runs directly and through the gateway, which continues the trace
 * (Day 15). The parent of an /internal/saved-counts span is job-service's client span,
 * which this JVM never records.
 */
@AutoConfigureTracing
@Import(EndedSpans.Config.class)
class JobServiceObservedIT extends IntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private EndedSpans spans;

    @Test
    void oneSearchIsCountedOnJobServicesPortOnly() {
        String title = "CountedEngineer" + UUID.randomUUID().toString().substring(0, 8);
        var posting = aPosting().title(title).create();
        double searches = searches();
        double countCalls = countCalls();

        ApiResponse response = anonymous().get("/api/jobs?q=" + title);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/content/0/postingId").asText()).isEqualTo(posting.id());
        // The server's count is recorded as the response completes, so it can lag the response.
        poll(() -> Optional.of(true).filter(rose -> searches() > searches && countCalls() > countCalls));
        assertThat(searches()).as("/api/jobs on job-service's port").isEqualTo(searches + 1);
        assertThat(countCalls()).as("/internal/saved-counts on job-service's port").isEqualTo(countCalls + 1);
        assertThat(count(managementPort, "http_server_requests_seconds_count", "uri=\"/api/jobs\""))
                .as("/api/jobs on the monolith's port")
                .isZero();
    }

    @Test
    void aTraceCrossesIntoJobServiceAndBackToTheMonolith() {
        var posting = aPosting().title("TracedEngineer").create();
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        ApiResponse response = anonymous()
                .withHeader("traceparent", "00-" + traceId + "-" + parentSpanId + "-01")
                .get("/api/jobs/" + posting.id());

        assertThat(response.status()).isEqualTo(200);

        SpanData internalSpan = poll(() -> {
            List<SpanData> allSpans = spans.all();
            return allSpans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER)
                    .filter(span -> traceId.equals(span.getTraceId()))
                    .filter(span -> span.getName().endsWith("/internal/saved-counts"))
                    .findFirst();
        }).orElseThrow(() -> {
            List<SpanData> allSpans = spans.all();
            List<String> serverSpanNamesWithTraceT = allSpans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER)
                    .filter(span -> traceId.equals(span.getTraceId()))
                    .map(SpanData::getName)
                    .toList();
            List<String> savedCountsTraceIds = allSpans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER)
                    .filter(span -> span.getName().endsWith("/internal/saved-counts"))
                    .map(SpanData::getTraceId)
                    .toList();
            return new AssertionError("No /internal/saved-counts span found with trace id " + traceId
                    + ". Server span names with trace T: " + serverSpanNamesWithTraceT
                    + "; trace ids of /internal/saved-counts spans: " + savedCountsTraceIds);
        });

        List<String> monolithSpanIds = spans.all().stream()
                .map(SpanData::getSpanId)
                .toList();

        assertThat(SpanId.isValid(internalSpan.getParentSpanId()))
                .as("parent span id is valid").isTrue();
        assertThat(monolithSpanIds).as("parent is not in monolith's spans")
                .doesNotContain(internalSpan.getParentSpanId());
    }

    private static double count(int port, String metric, String... labels) {
        String prometheus = ApiClient.onPort(port).get("/actuator/prometheus").body();
        return prometheus.lines()
                .filter(line -> line.startsWith(metric + "{"))
                .filter(line -> Arrays.stream(labels).allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    private double searches() {
        return count(JobService.managementPort(), "http_server_requests_seconds_count",
                "uri=\"/api/jobs\"", "status=\"200\"");
    }

    private double countCalls() {
        return count(JobService.managementPort(), "http_client_requests_seconds_count",
                "uri=\"/internal/saved-counts\"", "status=\"200\"");
    }

    /** What {@code probe} finds within 5 seconds, asked every 100 ms; empty if it finds nothing. */
    private static <T> Optional<T> poll(Supplier<Optional<T>> probe) {
        long deadline = System.currentTimeMillis() + 5000;
        Optional<T> found = probe.get();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            found = probe.get();
        }
        return found;
    }

    private static String randomTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

}

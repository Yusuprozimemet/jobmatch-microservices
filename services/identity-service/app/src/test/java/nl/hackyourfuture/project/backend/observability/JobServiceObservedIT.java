package nl.hackyourfuture.project.backend.observability;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.ApplicationService;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.web.server.LocalManagementPort;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * job-service is measured on its own port, and a trace crosses into it (Day 17, Track E2;
 * Day 40's hand-off). It runs directly and through the gateway, which continues the trace
 * (Day 15). Its /internal/saved-counts call goes on to application-service (Day 25), which
 * logs it under the same trace.
 */
@AutoConfigureTracing
class JobServiceObservedIT extends IntegrationTest {

    @LocalManagementPort
    private int managementPort;

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
    void aTraceCrossesIntoJobServiceAndOnToApplicationService() throws InterruptedException {
        var posting = aPosting().title("TracedEngineer").create();
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();

        ApiResponse response = anonymous()
                .withHeader("traceparent", "00-" + traceId + "-" + parentSpanId + "-01")
                .get("/api/jobs/" + posting.id());

        assertThat(response.status()).isEqualTo(200);

        List<String> savedCountsLines = linesUnder(ApplicationService::logs, traceId, "/internal/saved-counts");
        assertThat(savedCountsLines)
                .as("application-service logs /internal/saved-counts under the same trace")
                .isNotEmpty();
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

    /** Log lines from logs() containing path and traceId; poll up to 5 s. */
    private static List<String> linesUnder(Supplier<String> logs, String traceId, String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = logs.get().lines().filter(line -> line.contains(traceId) && line.contains(path)).toList();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            found = logs.get().lines().filter(line -> line.contains(traceId) && line.contains(path)).toList();
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

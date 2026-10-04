package nl.hackyourfuture.project.backend.observability;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the application exposes about itself, and to whom.
 *
 * <p>Actuator listens on its own port. Compose and the ingress do not publish it, so a
 * Prometheus server on the internal network can scrape without the application growing a
 * public metrics endpoint, and without a second set of credentials to manage. On the
 * application port every {@code /actuator/**} path falls through to
 * {@code anyRequest().authenticated()} and answers 401.
 *
 * <p>The LLM call's span and metric are checked by {@code LlmCallObservedIT}, and JDBC spans
 * were dropped, both on Day 38. Correlated log lines need the agent and a collector, and
 * are checked by hand ({@code ObservabilityLoggingIT} pins the application's half). Everything
 * that can be a gate is here, so removing the actuator dependency later fails the build rather
 * than a dashboard nobody opens.
 *
 * <p>This tests one deployable's actuator, not the API, so it lives beside the monolith's own
 * tests rather than in {@code contract/} (Day 40): the requests it sends go to a path the
 * monolith keeps, {@code /.well-known/jwks.json}, not a job path, which another service will
 * serve.
 */
class ObservabilityIT extends IntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Test
    void healthIsAvailableOnTheManagementPortWithoutCredentials() {
        ApiResponse response = management().get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/status").asString()).isEqualTo("UP");
    }

    // The database is part of being healthy: an application that cannot reach Postgres
    // should not be taking traffic.
    @Test
    void healthReportsTheDatabase() {
        ApiResponse response = management().get("/actuator/health");

        assertThat(response.at("/components/db/status").asString()).isEqualTo("UP");
    }

    @Test
    void exposesRequestTimingsForPrometheusToScrape() {
        double before = requestCount("/.well-known/jwks.json");

        // A request to measure, on the application port.
        assertThat(anonymous().get("/.well-known/jwks.json").status()).isEqualTo(200);

        ApiResponse metrics = management().get("/actuator/prometheus");
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body()).contains("http_server_requests_seconds");
        assertThat(requestCount(metrics.body(), "/.well-known/jwks.json")).isGreaterThan(before);
    }

    @Test
    void reportsJdbcPoolAndJvmMetricsToo() {
        ApiResponse metrics = management().get("/actuator/prometheus");

        assertThat(metrics.body())
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections");
    }

    // The whole point of the separate port: none of this is on the port the world can reach.
    @Test
    void exposesNothingOnTheApplicationPort() {
        assertThat(anonymous().get("/actuator/health").status()).isEqualTo(401);
        assertThat(anonymous().get("/actuator/prometheus").status()).isEqualTo(401);
    }

    // Only health and prometheus are exposed at all, so the endpoint that would print the
    // whole configuration is not served even on the management port.
    // Only health and prometheus are exposed, and EndpointRequest matches only what is
    // exposed - so the chain that permits actuator never sees these, and they are refused
    // rather than served. Refused, not 404: the caller is not told what does not exist.
    @Test
    void doesNotExposeTheEnvironmentOrAnythingElse() {
        assertThat(management().get("/actuator/env").status()).isEqualTo(401);
        assertThat(management().get("/actuator/beans").status()).isEqualTo(401);
        assertThat(management().get("/actuator/configprops").status()).isEqualTo(401);
        assertThat(anonymous().get("/actuator/env").status()).isEqualTo(401);
    }

    /**
     * Mail is deliberately not part of being healthy.
     *
     * <p>The indicator opens an SMTP connection on every check and reports DOWN when the
     * relay is unreachable, taking the whole endpoint DOWN with it. Phase 7 makes this the
     * Kubernetes readiness probe, so leaving it on would pull the application out of the load
     * balancer - nobody could browse jobs - because password-reset email is undeliverable.
     */
    @Test
    void doesNotLetAnUnreachableMailRelayMarkTheApplicationUnhealthy() {
        ApiResponse health = management().get("/actuator/health");

        // No mail server is running in the test profile, and health is still UP.
        assertThat(health.at("/status").asString()).isEqualTo("UP");
        assertThat(health.at("/components/mail").isMissingNode()).isTrue();
    }

    /**
     * The test profile configures no OTLP collector, and this whole suite runs — so an
     * application with nowhere to send telemetry still starts and serves traffic.
     *
     * <p>Worth its own test because the default is the opposite of what the day's spec first
     * assumed: Micrometer's OTLP registry exports to a local collector unless told not to, so
     * "no endpoint configured" would otherwise mean "retry forever against nothing".
     */
    @Test
    void servesTrafficWithNoCollectorConfigured() {
        assertThat(anonymous().get("/.well-known/jwks.json").status()).isEqualTo(200);
        assertThat(management().get("/actuator/health").at("/status").asString()).isEqualTo("UP");
    }

    private double requestCount(String uri) {
        return requestCount(management().get("/actuator/prometheus").body(), uri);
    }

    private static double requestCount(String prometheus, String uri) {
        // Summed across status and outcome labels.
        return prometheus.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count{")
                        && line.contains("uri=\"" + uri + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    private ApiClient management() {
        return ApiClient.onPort(managementPort);
    }
}

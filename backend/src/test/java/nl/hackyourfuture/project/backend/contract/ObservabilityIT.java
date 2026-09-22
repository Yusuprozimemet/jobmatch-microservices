package nl.hackyourfuture.project.backend.contract;

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
 * <p>Day 05's other three criteria — a trace spanning the controller and its JDBC calls,
 * correlated log lines, and the LLM call as its own span — need a collector and a browser.
 * They are checked by hand, and the commands are in the day's spec. Everything that can be a
 * gate is here, so removing the actuator dependency later fails the build rather than a
 * dashboard nobody opens.
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
        // A request to measure, on the application port.
        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);

        ApiResponse metrics = management().get("/actuator/prometheus");

        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("http_server_requests_seconds")
                .contains("uri=\"/api/jobs\"");
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
        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);
        assertThat(management().get("/actuator/health").at("/status").asString()).isEqualTo("UP");
    }

    private ApiClient management() {
        return ApiClient.onPort(managementPort);
    }
}

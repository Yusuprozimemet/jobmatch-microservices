package nl.hackyourfuture.project.backend.internal;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.support.ApplicationService;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.EventBus;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.JobService;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import nl.hackyourfuture.project.backend.support.ServiceKey;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness's application-service container (Day 25, Track C2); /api/nothing and /internal/nothing
 * are routes no service will ever have, so a token that gets past a chain gets 404 there.
 */
class ApplicationServiceHarnessIT extends IntegrationTest {

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void theContainerPublishesTheKeyTheMonolithTrusts() throws ParseException {
        ApiResponse response = ApiClient.at(ApplicationService.baseUrl()).get("/.well-known/service-jwks.json");

        assertThat(response.status()).isEqualTo(200);
        JWKSet containerKeySet = JWKSet.parse(response.body());
        ApiResponse monolithKeyResponse = ApiClient.at(ServiceKey.APPLICATION.jwksUrl()).get("");
        JWKSet monolithKeySet = JWKSet.parse(monolithKeyResponse.body());

        assertThat(containerKeySet.getKeys()).isNotEmpty();
        RSAKey containerKey = (RSAKey) containerKeySet.getKeys().get(0);
        RSAKey monolithKey = (RSAKey) monolithKeySet.getKeys().get(0);

        assertThat(containerKey.getKeyID()).isEqualTo(monolithKey.getKeyID());
        assertThat(containerKey.getModulus()).isEqualTo(monolithKey.getModulus());
        assertThat(containerKey.getPublicExponent()).isEqualTo(monolithKey.getPublicExponent());
    }

    @Test
    void aUsersTokenGetsPastTheChain() {
        TestUser user = aUser().create();
        String token = accessTokens.mint(user.id(), user.email());

        ApiResponse response = ApiClient.at(ApplicationService.baseUrl())
                .withCookie("access_token", token)
                .get("/api/nothing");

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        ApiResponse response = ApiClient.at(ApplicationService.baseUrl()).get("/api/nothing");

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aRequestIsLoggedUnderItsTraceId() throws InterruptedException {
        TestUser user = aUser().create();
        String token = accessTokens.mint(user.id(), user.email());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ApiClient.at(ApplicationService.baseUrl())
                .withCookie("access_token", token)
                .withHeader("traceparent", "00-" + traceId + "-" + spanId + "-01")
                .get("/api/nothing");

        assertThat(traceLinesUnder(traceId)).as("application-service's /api/nothing under trace " + traceId
                + "; its lines for that route: " + traceLines()).isNotEmpty();
    }

    @Test
    void jobServicesTokenGetsPastTheInternalChain() {
        ApiResponse response = ApiClient.at(ApplicationService.baseUrl())
                .withHeader("Authorization", "Bearer " + JobService.mintToken())
                .get("/internal/nothing");

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void theTestCallersTokenGetsPastTheInternalChain() {
        ApiResponse response = ApiClient.at(ApplicationService.baseUrl())
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token())
                .get("/internal/nothing");

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void noServiceTokenIs401OnTheInternalChain() {
        ApiResponse response = ApiClient.at(ApplicationService.baseUrl()).get("/internal/nothing");

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void itsFlywayMigratedAppsDb() {
        ApplicationService.baseUrl();

        JdbcClient jdbcClient = JdbcClient.create(PostgresContainer.appsDataSource());

        String savedJobsTable = jdbcClient
                .sql("SELECT to_regclass('applications.saved_jobs')::text")
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(savedJobsTable).isNotNull();

        long migrationCount = jdbcClient
                .sql("SELECT count(*) FROM applications.flyway_schema_history WHERE success")
                .query(Long.class)
                .single();
        assertThat(migrationCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void itsConsumerIsGivenAQueueOfItsOwn() {
        String consumerQueueUrl = ApplicationService.consumerQueueUrl();

        assertThat(consumerQueueUrl).endsWith("/" + EventBus.APPLICATION_SERVICE_QUEUE);

        String queuePath = URI.create(EventBus.queueUrl(EventBus.APPLICATION_SERVICE_QUEUE)).getPath();
        assertThat(URI.create(consumerQueueUrl).getPath()).isEqualTo(queuePath);

        assertThat(URI.create(consumerQueueUrl).getHost()).isEqualTo("host.testcontainers.internal");
        assertThat(URI.create(consumerQueueUrl).getPort()).isEqualTo(EventBus.endpoint().getPort());

        assertThat(consumerQueueUrl).doesNotEndWith("/" + EventBus.APPLICATIONS_QUEUE);
    }

    private static List<String> traceLinesUnder(String traceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = traceLines().stream().filter(line -> line.contains(traceId)).toList();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            found = traceLines().stream().filter(line -> line.contains(traceId)).toList();
        }
        return found;
    }

    private static List<String> traceLines() {
        return ApplicationService.logs().lines().filter(line -> line.contains("/api/nothing")).toList();
    }
}

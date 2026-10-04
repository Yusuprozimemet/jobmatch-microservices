package nl.hackyourfuture.project.backend.internal;

import com.sun.net.httpserver.HttpServer;
import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.ApplicationService;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.JobService;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * job-service and the monolith trust each other, and only whom they list (Day 17, Tracks C2 and D), with
 * job-service in its container ({@link JobService}). It has no {@code /internal/nothing}: a trusted
 * caller gets 404 there, which a chain that refuses everyone, as {@code denyAll()} would, cannot give.
 */
class JobServiceHarnessIT extends IntegrationTest {

    @Autowired
    private ServiceToken serviceToken;

    @Test
    void aJobmatchJobServiceTokenReachesApplicationServicesSavedCounts() {
        ApiClient client = ApiClient.at(ApplicationService.baseUrl()).withHeader("Authorization", "Bearer " + JobService.mintToken());

        ApiResponse response = client.post("/internal/saved-counts", Map.of("ids", List.of()));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void theMonolithsOwnTokenGetsPastJobServicesChain() {
        assertThat(postToJobServiceInternal("Bearer " + serviceToken.mint())).isEqualTo(404);
    }

    @Test
    void theMonolithsOwnTokenGetsPostingsFromJobService() {
        ApiClient client = ApiClient.at(JobService.baseUrl()).withHeader("Authorization", "Bearer " + serviceToken.mint());

        ApiResponse response = client.post("/internal/postings/batch", Map.of("ids", List.of("seed-0001")));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void theListedTestCallerGetsPastJobServicesChain() {
        assertThat(postToJobServiceInternal("Bearer " + TestServiceCaller.instance().token())).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        assertThat(postToJobServiceInternal(null)).isEqualTo(401);
    }

    @Test
    void anExpiredTokenIs401() {
        assertThat(postToJobServiceInternal("Bearer " + TestServiceCaller.instance().expired())).isEqualTo(401);
    }

    @Test
    void anUnlistedIssuerIs401() {
        assertThat(postToJobServiceInternal(
                "Bearer " + TestServiceCaller.instance().withIssuer("jobmatch-stranger"))).isEqualTo(401);
    }

    @Test
    void jobServiceDoesNotTrustItsOwnToken() {
        assertThat(postToJobServiceInternal("Bearer " + JobService.mintToken())).isEqualTo(401);
    }

    @Test
    void anonymousGetsTheKeySet() {
        ApiClient client = ApiClient.at(JobService.baseUrl());

        ApiResponse response = client.get("/.well-known/service-jwks.json");

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void theRelayForwardsToWhateverItIsPointedAt() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/probe", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"stub\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            JobService.relayTo(stub.getAddress().getPort());

            ApiClient client = ApiClient.at(JobService.relayBaseUrl()).withHeader("Authorization", "Bearer probe-token");
            ApiResponse response = client.post("/internal/probe", Map.of());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("stub");
            assertThat(seenAuth.get()).isEqualTo("Bearer probe-token");
        } finally {
            stub.stop(0);
        }
    }

    private int postToJobServiceInternal(String bearer) {
        ApiClient client = ApiClient.at(JobService.baseUrl());
        if (bearer != null) {
            client = client.withHeader("Authorization", bearer);
        }
        return client.post("/internal/nothing", Map.of()).status();
    }
}

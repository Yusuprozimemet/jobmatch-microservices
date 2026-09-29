package nl.hackyourfuture.project.backend.internal;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.MatchingService;
import nl.hackyourfuture.project.backend.support.MatchingServiceKey;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.ParseException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness's matching-service container (Day 21, Track C2); /api/nothing is a route no service
 * will ever have, so a token that gets past the chain gets 404 there.
 */
class MatchingServiceHarnessIT extends IntegrationTest {

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void theContainerPublishesTheKeyTheMonolithTrusts() throws ParseException {
        ApiResponse response = ApiClient.at(MatchingService.baseUrl()).get("/.well-known/service-jwks.json");

        assertThat(response.status()).isEqualTo(200);
        JWKSet containerKeySet = JWKSet.parse(response.body());
        ApiResponse monolithKeyResponse = ApiClient.at(MatchingServiceKey.jwksUrl()).get("");
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

        ApiResponse response = ApiClient.at(MatchingService.baseUrl())
                .withCookie("access_token", token)
                .get("/api/nothing");

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        ApiResponse response = ApiClient.at(MatchingService.baseUrl()).get("/api/nothing");

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aRequestIsLoggedUnderItsTraceId() throws InterruptedException {
        TestUser user = aUser().create();
        String token = accessTokens.mint(user.id(), user.email());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ApiClient.at(MatchingService.baseUrl())
                .withCookie("access_token", token)
                .withHeader("traceparent", "00-" + traceId + "-" + spanId + "-01")
                .get("/api/nothing");

        assertThat(traceLinesUnder(traceId)).as("matching-service's /api/nothing under trace " + traceId
                + "; its lines for that route: " + traceLines()).isNotEmpty();
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
        return MatchingService.logs().lines().filter(line -> line.contains("/api/nothing")).toList();
    }
}

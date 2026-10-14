package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.JobService;
import nl.hackyourfuture.project.backend.support.ServiceKey;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * matching-service's tokens reach the routes it calls (Day 21, Track C1): identity's existence and
 * profile routes in the monolith, and job-service's shortlist in its container. Both trust
 * {@code jobmatch-matching-service} at {@link ServiceKey#MATCHING}'s key set, and only with that key:
 * the name alone, signed by another key, is 401.
 */
class MatchingServiceTrustIT extends IntegrationTest {

    @Test
    void matchingServicesTokenReachesIdentitysUserRoute() {
        TestUser user = aUser().create();

        ApiResponse response = direct()
                .withHeader("Authorization", "Bearer " + ServiceKey.MATCHING.mintToken())
                .get("/internal/users/" + user.id());

        assertThat(response.status()).isEqualTo(204);
    }

    @Test
    void matchingServicesTokenReachesIdentitysProfileRoute() {
        TestUser user = aUser().create();
        aProfile().forUser(user).create();

        ApiResponse response = direct()
                .withHeader("Authorization", "Bearer " + ServiceKey.MATCHING.mintToken())
                .get("/internal/profiles/" + user.id());

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void matchingServicesTokenGetsAShortlistFromJobService() {
        ApiClient client = ApiClient.at(JobService.baseUrl())
                .withHeader("Authorization", "Bearer " + ServiceKey.MATCHING.mintToken());

        ApiResponse response = client.post("/internal/postings/shortlist", Map.of("skills", List.of("java"), "limit", 5));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void aMatchingServiceIssuerSignedWithAnotherKeyIsUnauthorizedAgainstTheMonolith() {
        String tokenWithWrongKey = TestServiceCaller.instance().withIssuer(ServiceKey.MATCHING.issuer());

        ApiResponse response = direct()
                .withHeader("Authorization", "Bearer " + tokenWithWrongKey)
                .get("/internal/users/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aMatchingServiceIssuerSignedWithAnotherKeyIsUnauthorizedAgainstJobService() {
        String tokenWithWrongKey = TestServiceCaller.instance().withIssuer(ServiceKey.MATCHING.issuer());
        ApiClient client = ApiClient.at(JobService.baseUrl())
                .withHeader("Authorization", "Bearer " + tokenWithWrongKey);

        ApiResponse response = client.post("/internal/nothing", Map.of());

        assertThat(response.status()).isEqualTo(401);
    }
}

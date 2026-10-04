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
 * application-service's tokens reach the routes it calls (Day 25, Track C1): identity's existence
 * route in the monolith and job-service's posting batch in its container. Both trust
 * {@code jobmatch-application-service} at {@link ServiceKey#APPLICATION}'s key set, and only with that key:
 * the name alone, signed by another key, is 401.
 */
class ApplicationServiceTrustIT extends IntegrationTest {

    @Test
    void applicationServicesTokenReachesIdentitysUserRoute() {
        TestUser user = aUser().create();

        ApiResponse response = direct()
                .withHeader("Authorization", "Bearer " + ServiceKey.APPLICATION.mintToken())
                .get("/internal/users/" + user.id());

        assertThat(response.status()).isEqualTo(204);
    }

    @Test
    void applicationServicesTokenGetsPostingsFromJobService() {
        ApiClient client = ApiClient.at(JobService.baseUrl())
                .withHeader("Authorization", "Bearer " + ServiceKey.APPLICATION.mintToken());

        ApiResponse response = client.post("/internal/postings/batch", Map.of("ids", List.of()));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void anApplicationServiceIssuerSignedWithAnotherKeyIsUnauthorizedAgainstTheMonolith() {
        String tokenWithWrongKey = TestServiceCaller.instance().withIssuer(ServiceKey.APPLICATION.issuer());

        ApiResponse response = direct()
                .withHeader("Authorization", "Bearer " + tokenWithWrongKey)
                .get("/internal/users/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void anApplicationServiceIssuerSignedWithAnotherKeyIsUnauthorizedAgainstJobService() {
        String tokenWithWrongKey = TestServiceCaller.instance().withIssuer(ServiceKey.APPLICATION.issuer());
        ApiClient client = ApiClient.at(JobService.baseUrl())
                .withHeader("Authorization", "Bearer " + tokenWithWrongKey);

        ApiResponse response = client.post("/internal/postings/batch", Map.of("ids", List.of()));

        assertThat(response.status()).isEqualTo(401);
    }
}

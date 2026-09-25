package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /internal/** accepts service tokens only (Day 39), and nothing else. The path is unmapped on
 * purpose: 404 means security let it through; 401 means it was refused. Tests use a call on direct()
 * because the gateway never routes /internal.
 */
class InternalRoutesIT extends IntegrationTest {

    private static final String INTERNAL_PATH = "/internal/no-route-yet";

    @Autowired
    private ServiceToken serviceToken;

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void theMonolithsOwnTokenPassesSecurity() {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(404);
    }

    @Test
    void aListedCallersTokenPassesSecurity() {
        ApiClient client = direct()
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(404);
    }

    @Test
    void aUsersCookieIsNotEnough() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAsOnDirect(user);

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void noTokenIsRefused() {
        assertThat(direct().get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIsRefused() {
        String userToken = accessTokens.mint(UUID.randomUUID(), "x@example.test");
        ApiClient client = direct().withHeader("Authorization", "Bearer " + userToken);

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void aCallersTokenSignedByAnotherKeyIsRefused() {
        ApiClient client = direct()
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().signedByAnotherKey());

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void anExpiredTokenIsRefused() {
        ApiClient client = direct()
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().expired());

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        ApiClient client = direct()
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().forAudience("jobmatch-api"));

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void aTokenFromAnIssuerNotOnTheListIsRefused() {
        ApiClient client = direct()
                .withHeader("Authorization", "Bearer " + TestServiceCaller.instance().withIssuer("jobmatch-unknown"));

        assertThat(client.get(INTERNAL_PATH).status()).isEqualTo(401);
    }

    @Test
    void aServiceTokenIsNotALoginOnTheApi() {
        // Service token in the header - should not work.
        ApiClient clientWithHeader = direct()
                .withHeader("Authorization", "Bearer " + serviceToken.mint());
        assertThat(clientWithHeader.get("/api/users/me").status()).isEqualTo(401);

        // Service token as a cookie - should not work either.
        ApiClient clientWithCookie = direct().withCookie("access_token", serviceToken.mint());
        assertThat(clientWithCookie.get("/api/users/me").status()).isEqualTo(401);
    }

    private ApiClient authenticatedAsOnDirect(TestUser user) {
        if (user.password() == null) {
            throw new IllegalArgumentException(
                    "User " + user.email() + " has no password (Google-only account), so it cannot log in");
        }
        ApiClient client = direct();
        var response = client.post("/api/auth/login",
                Map.of("email", user.email(), "password", user.password()));
        if (response.status() != 200) {
            throw new IllegalStateException("Could not log in as " + user.email()
                    + ": login returned " + response.status() + " " + response.body());
        }
        return client;
    }
}

package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity answers whether a user exists (Day 39), so a service trusting {@code sub} can refuse
 * a deleted user as {@code SessionWithoutAUserIT} requires of the monolith. {@code GET
 * /internal/users/{id}} answers 204 while the user exists, 404 once deleted, behind the
 * {@code /internal/**} chain with service tokens only, never cached.
 */
class InternalUsersIT extends IntegrationTest {

    @Autowired
    private ServiceToken serviceToken;

    @Test
    void aUserWhoExistsIs204() {
        TestUser user = aUser().create();
        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        assertThat(client.get("/internal/users/" + user.id()).status()).isEqualTo(204);
    }

    @Test
    void theMonolithsOwnTokenIsTrustedToo() {
        TestUser user = aUser().create();
        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        assertThat(client.get("/internal/users/" + user.id()).status()).isEqualTo(204);
    }

    @Test
    void aDeletedUserIs404() {
        TestUser user = aUser().create();
        ApiClient service = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());
        assertThat(service.get("/internal/users/" + user.id()).status()).isEqualTo(204);

        assertThat(authenticatedAsOnDirect(user).delete("/api/users/me").status()).isEqualTo(204);

        assertThat(service.get("/internal/users/" + user.id()).status()).isEqualTo(404);
    }

    @Test
    void anIdNeverSeenIs404() {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        assertThat(client.get("/internal/users/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        TestUser user = aUser().create();

        assertThat(direct().get("/internal/users/" + user.id()).status()).isEqualTo(401);
    }

    @Test
    void theUsersOwnCookieIs401() {
        TestUser user = aUser().create();
        ApiClient authenticated = authenticatedAsOnDirect(user);

        assertThat(authenticated.get("/internal/users/" + user.id()).status()).isEqualTo(401);
    }

    @Test
    void theOpenApiListsNoInternalPath() {
        ApiClient client = direct();
        String openapi = client.get("/api/docs/openapi.yaml").body();

        assertThat(openapi)
                .as("OpenAPI should list no /internal/ path")
                .doesNotContain("/internal/");
        assertThat(openapi)
                .as("OpenAPI should not list service-jwks")
                .doesNotContain("service-jwks");
        // But it should still list public routes.
        assertThat(openapi)
                .as("OpenAPI should list /.well-known/jwks.json")
                .contains("/.well-known/jwks.json");
        assertThat(openapi)
                .as("OpenAPI should list /api/users/me")
                .contains("/api/users/me");
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

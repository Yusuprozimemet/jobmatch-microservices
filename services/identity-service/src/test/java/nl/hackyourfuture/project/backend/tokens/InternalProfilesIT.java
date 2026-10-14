package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity answers a user's profile snapshot (Day 41), so matching can rank without reading
 * {@code user_profiles} itself. {@code GET /internal/profiles/{userId}} answers 200 with the
 * skills and preferred city, 404 for a user with no profile, behind the {@code /internal/**}
 * chain with service tokens only: the user's own cookie or access token is 401.
 */
class InternalProfilesIT extends IntegrationTest {

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void aUserWithAProfileIs200WithSkillsAndCity() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity("Amsterdam").skills("java", "sql", "docker", "react", "go").create();

        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        var response = client.get("/internal/profiles/" + user.id());

        assertThat(response.status()).isEqualTo(200);
        List<String> skills = new ArrayList<>();
        response.at("/skills").forEach(skill -> skills.add(skill.asString()));
        assertThat(skills).containsExactlyInAnyOrder("java", "sql", "docker", "react", "go");
        assertThat(response.at("/preferredCity").asString()).isEqualTo("Amsterdam");
    }

    @Test
    void aUserWithoutAProfileIs404() {
        TestUser user = aUser().create();

        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        assertThat(client.get("/internal/profiles/" + user.id()).status()).isEqualTo(404);
    }

    @Test
    void anIdNeverSeenIs404() {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        assertThat(client.get("/internal/profiles/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity("Amsterdam").skills("java", "sql", "docker", "react", "go").create();

        assertThat(direct().get("/internal/profiles/" + user.id()).status()).isEqualTo(401);
    }

    @Test
    void theUsersOwnCookieIs401() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity("Amsterdam").skills("java", "sql", "docker", "react", "go").create();

        ApiClient authenticated = authenticatedAsOnDirect(user);

        assertThat(authenticated.get("/internal/profiles/" + user.id()).status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity("Amsterdam").skills("java", "sql", "docker", "react", "go").create();

        String userToken = accessTokens.mint(user.id(), user.email());
        ApiClient client = direct().withHeader("Authorization", "Bearer " + userToken);

        assertThat(client.get("/internal/profiles/" + user.id()).status()).isEqualTo(401);
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

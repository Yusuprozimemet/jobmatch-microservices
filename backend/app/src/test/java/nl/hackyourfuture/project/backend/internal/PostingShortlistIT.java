package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.ShortlistFixture;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /internal/postings/shortlist} (Day 18): {@link PostingShortlist#shortlist} over HTTP,
 * to service tokens only, held to the order {@code ShortlistOrderIT} pins in process on the same
 * {@link ShortlistFixture}. No skills, or a limit outside 1-100, is a 400 rather than a failed or
 * unbounded query.
 */
class PostingShortlistIT extends IntegrationTest {

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private ServiceToken serviceToken;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final Map<String, Object> REQUEST =
            Map.of("city", ShortlistFixture.CITY, "skills", ShortlistFixture.SKILLS, "limit", ShortlistFixture.LIMIT);

    @BeforeEach
    void createTheFixture() {
        ShortlistFixture.create(jdbc());
    }

    @Test
    void ranksTheFixtureAsTrackZeroPinnedItWithTheMonolithsOwnToken() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.isArray()).isTrue();

        List<String> postingIds = new ArrayList<>();
        for (JsonNode item : body) {
            postingIds.add(item.at("/postingId").asText());
        }
        assertThat(postingIds).isEqualTo(ShortlistFixture.EXPECTED);
    }

    @Test
    void theWholeJsonBodyIsTheFixturesShortlist() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(200);

        LocalDate today = jdbc().sql("SELECT current_date").query(LocalDate.class).single();
        JsonNode expectedJson = JSON.valueToTree(ShortlistFixture.expectedRows(today));
        assertThat(response.json()).isEqualTo(expectedJson);
    }

    @Test
    void answersAListedCallerToo() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(200);
        JsonNode firstItem = response.json().at("/0");
        assertThat(firstItem.at("/postingId").asText()).isEqualTo(ShortlistFixture.EXPECTED.get(0));
    }

    @Test
    void skillsEmptyListIs400() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                Map.of("city", ShortlistFixture.CITY, "skills", List.of(), "limit", ShortlistFixture.LIMIT));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void skillsMissingIs400() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                Map.of("city", ShortlistFixture.CITY, "limit", ShortlistFixture.LIMIT));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void limit0Is400() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                Map.of("city", ShortlistFixture.CITY, "skills", ShortlistFixture.SKILLS, "limit", 0));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void limit101Is400() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/shortlist",
                Map.of("city", ShortlistFixture.CITY, "skills", ShortlistFixture.SKILLS, "limit", 101));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void noTokenIs401() {
        ApiClient client = inNetwork();

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theUsersCookieIs401() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedInNetwork(user);

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() {
        String userToken = accessTokens.mint(UUID.randomUUID(), "x@example.test");
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + userToken);

        var response = client.post("/internal/postings/shortlist",
                REQUEST);

        assertThat(response.status()).isEqualTo(401);
    }

    private ApiClient authenticatedInNetwork(TestUser user) {
        if (user.password() == null) {
            throw new IllegalArgumentException(
                    "User " + user.email() + " has no password (Google-only account), so it cannot log in");
        }
        ApiClient client = inNetwork();
        var loginResponse = client.post("/api/auth/login",
                Map.of("email", user.email(), "password", user.password()));
        if (loginResponse.status() != 200) {
            throw new IllegalStateException("Could not log in as " + user.email()
                    + ": login returned " + loginResponse.status() + " " + loginResponse.body());
        }
        return client;
    }
}

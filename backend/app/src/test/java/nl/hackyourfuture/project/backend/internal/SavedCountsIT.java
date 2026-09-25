package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StatementCounter;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /internal/saved-counts} (Day 18): {@link SavedJobCounts#countsFor} over HTTP, to
 * service tokens only. What Day 19's client relies on: one entry per distinct id, 0 for nobody,
 * {@code {}} without a query, and more than 500 distinct ids is a 400 before any query.
 */
class SavedCountsIT extends IntegrationTest {

    @Autowired @Qualifier("applicationsDirectory")
    private SavedJobCounts savedJobCounts; // The in-process implementation, not the @Primary HTTP client.

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private ServiceToken serviceToken;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void answersWhatTheCountsAnswerInProcessWithTheMonolithsOwnToken() {
        String id1 = aPosting().id("saved-a").create().id();
        String id2 = aPosting().id("saved-b").create().id();
        String id3 = "saved-not-in-mart";

        TestUser user1 = aUser().create();
        TestUser user2 = aUser().create();

        save(user1, id1);
        save(user2, id1);
        save(user1, id2);

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of(id1, id2, id3, id3)));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get(id1).asInt()).isEqualTo(2);
        assertThat(body.get(id2).asInt()).isEqualTo(1);
        assertThat(body.get(id3).asInt()).isEqualTo(0);

        Map<String, Integer> inProcess = savedJobCounts.countsFor(List.of(id1, id2, id3, id3));
        JsonNode inProcessJson = JSON.valueToTree(inProcess);
        assertThat(body).isEqualTo(inProcessJson);
    }

    @Test
    void answersAListedCallerToo() {
        String id = aPosting().id("saved-c").create().id();
        TestUser user = aUser().create();

        save(user, id);

        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of(id)));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/" + id).asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyListIsAnEmptyObjectWithoutAQuery() {
        StatementCounter.reset();

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().size()).isZero();
        assertThat(StatementCounter.statementsMentioning("saved_jobs")).isZero();
    }

    @Test
    void moreThan500DistinctIdsIs400BeforeAnyQuery() {
        StatementCounter.reset();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add("saved-id-" + i);
        }

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/saved-counts",
                Map.of("ids", ids));

        assertThat(response.status()).isEqualTo(400);
        assertThat(StatementCounter.statementsMentioning("saved_jobs")).isZero();
    }

    @Test
    void duplicatesDoNotCountTowardTheCap() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add("saved-dup-" + i);
        }
        // 550 entries, 500 of them distinct.
        for (int i = 0; i < 50; i++) {
            ids.add("saved-dup-" + i);
        }

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/saved-counts",
                Map.of("ids", ids));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void aBodyWithoutIdsIs400() {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/saved-counts", Map.of());

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void noTokenIs401() {
        ApiClient client = direct();

        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of("saved-d")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theUsersCookieIs401() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAsOnDirect(user);

        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of("saved-e")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() {
        String userToken = accessTokens.mint(UUID.randomUUID(), "x@example.test");
        ApiClient client = direct().withHeader("Authorization", "Bearer " + userToken);

        var response = client.post("/internal/saved-counts",
                Map.of("ids", List.of("saved-f")));

        assertThat(response.status()).isEqualTo(401);
    }

    /** A user cannot save a posting twice: {@code saved_jobs}' key is (user, posting). */
    private void save(TestUser user, String postingId) {
        jdbc().sql("INSERT INTO saved_jobs (user_id, posting_id) VALUES (:userId, :postingId)")
                .param("userId", user.id())
                .param("postingId", postingId)
                .update();
    }

    private ApiClient authenticatedAsOnDirect(TestUser user) {
        if (user.password() == null) {
            throw new IllegalArgumentException(
                    "User " + user.email() + " has no password (Google-only account), so it cannot log in");
        }
        ApiClient client = direct();
        var loginResponse = client.post("/api/auth/login",
                Map.of("email", user.email(), "password", user.password()));
        if (loginResponse.status() != 200) {
            throw new IllegalStateException("Could not log in as " + user.email()
                    + ": login returned " + loginResponse.status() + " " + loginResponse.body());
        }
        return client;
    }
}

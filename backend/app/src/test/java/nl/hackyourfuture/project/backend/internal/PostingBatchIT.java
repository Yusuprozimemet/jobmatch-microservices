package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.shared.jobs.PostingLookup;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
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
 * {@code POST /internal/postings/batch} (Day 18): {@link PostingLookup#byIds} over HTTP, to service
 * tokens only. What Day 19's client relies on: an id the mart does not have stays absent, which
 * {@code SavedJobHydrationIT}'s vanished-posting tests need; an empty list costs no query; and more
 * than 500 distinct ids is a 400 before any query, so the client splits a longer list.
 */
class PostingBatchIT extends IntegrationTest {

    @Autowired @Qualifier("jobsDirectory")
    private PostingLookup postingLookup; // The in-process implementation, not the @Primary HTTP client.

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private ServiceToken serviceToken;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void answersWhatTheLookupAnswersInProcessWithTheMonolithsOwnToken() {
        String id1 = aPosting().id("batch-a").create().id();
        String id2 = aPosting().id("batch-b").create().id();
        String id3 = "batch-not-in-mart";

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of(id1, id2, id3, id1)));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(id1)).isNotNull();
        assertThat(body.get(id2)).isNotNull();
        assertThat(body.get(id3)).isNull();

        Map<String, PostingSummary> inProcess = postingLookup.byIds(List.of(id1, id2, id3, id1));
        JsonNode inProcessJson = JSON.valueToTree(inProcess);
        assertThat(body).isEqualTo(inProcessJson);
    }

    @Test
    void answersAListedCallerToo() {
        String id = aPosting().id("batch-c").title("Test Title").create().id();

        ApiClient client = direct().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of(id)));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/" + id + "/title").asText()).isEqualTo("Test Title");
    }

    @Test
    void anEmptyListIsAnEmptyObjectWithoutAQuery() {
        StatementCounter.reset();

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().size()).isZero();
        assertThat(StatementCounter.statementsMentioning("fct_postings")).isZero();
    }

    @Test
    void moreThan500DistinctIdsIs400BeforeAnyQuery() {
        StatementCounter.reset();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add("batch-id-" + i);
        }

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/postings/batch",
                Map.of("ids", ids));

        assertThat(response.status()).isEqualTo(400);
        assertThat(StatementCounter.statementsMentioning("fct_postings")).isZero();
    }

    @Test
    void duplicatesDoNotCountTowardTheCap() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add("batch-dup-" + i);
        }
        // 550 entries, 500 of them distinct.
        for (int i = 0; i < 50; i++) {
            ids.add("batch-dup-" + i);
        }

        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/postings/batch",
                Map.of("ids", ids));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void aBodyWithoutIdsIs400() {
        ApiClient client = direct().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/batch", Map.of());

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void noTokenIs401() {
        ApiClient client = direct();

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-e")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theUsersCookieIs401() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAsOnDirect(user);

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-f")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() {
        String userToken = accessTokens.mint(UUID.randomUUID(), "x@example.test");
        ApiClient client = direct().withHeader("Authorization", "Bearer " + userToken);

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-g")));

        assertThat(response.status()).isEqualTo(401);
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

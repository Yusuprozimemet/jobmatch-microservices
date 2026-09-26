package nl.hackyourfuture.project.backend.internal;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StatementCounter;
import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestUser;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
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
 * {@code POST /internal/postings/batch} over HTTP (Day 18), to service tokens only. What Day 19's
 * client relies on: an id the mart does not have stays absent, which {@code SavedJobHydrationIT}'s
 * vanished-posting tests need; an empty list costs no query; and more than 500 distinct ids is a
 * 400 before any query, so the client splits a longer list.
 */
class PostingBatchIT extends IntegrationTest {

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private ServiceToken serviceToken;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void answersThePostingsWithTheMonolithsOwnToken() {
        String id1 = aPosting()
                .id("batch-a")
                .title("Software Engineer")
                .company("TechCorp")
                .cities("amsterdam")
                .skills("java", "spring")
                .category("software_engineering")
                .workMode("hybrid")
                .employmentType("permanent")
                .postedDaysAgo(5)
                .create().id();

        String id2 = aPosting()
                .id("batch-b")
                .title("Data Scientist")
                .company("DataInc")
                .cities("rotterdam", "amsterdam")
                .skills("python", "sql", "r")
                .category("data_science")
                .workMode("remote")
                .employmentType("contract")
                .postedDaysAgo(2)
                .create().id();

        String id3 = "batch-not-in-mart";

        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of(id1, id2, id3, id1)));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(id1)).isNotNull();
        assertThat(body.get(id2)).isNotNull();
        assertThat(body.get(id3)).isNull();

        LocalDate today = jdbc().sql("SELECT current_date").query(LocalDate.class).single();
        assertThat(body.get(id1)).isEqualTo(JSON.valueToTree(new PostingSummary("Software Engineer", "TechCorp",
                "Amsterdam", "hybrid", false, List.of("java", "spring"), "permanent", today.minusDays(5), "test",
                "software_engineering", "fresh", 5)));
        assertThat(body.get(id2)).isEqualTo(JSON.valueToTree(new PostingSummary("Data Scientist", "DataInc",
                "Rotterdam, Amsterdam", "remote", true, List.of("python", "sql", "r"), "contract", today.minusDays(2),
                "test", "data_science", "fresh", 2)));
    }

    @Test
    void answersAListedCallerToo() {
        String id = aPosting().id("batch-c").title("Test Title").create().id();

        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + TestServiceCaller.instance().token());

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of(id)));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/" + id + "/title").asText()).isEqualTo("Test Title");
    }

    @Test
    void anEmptyListIsAnEmptyObjectWithoutAQuery() {
        StatementCounter.reset();

        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());
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

        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());
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

        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());
        var response = client.post("/internal/postings/batch",
                Map.of("ids", ids));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void aBodyWithoutIdsIs400() {
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + serviceToken.mint());

        var response = client.post("/internal/postings/batch", Map.of());

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void noTokenIs401() {
        ApiClient client = inNetwork();

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-e")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theUsersCookieIs401() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedInNetwork(user);

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-f")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() {
        String userToken = accessTokens.mint(UUID.randomUUID(), "x@example.test");
        ApiClient client = inNetwork().withHeader("Authorization", "Bearer " + userToken);

        var response = client.post("/internal/postings/batch",
                Map.of("ids", List.of("batch-g")));

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

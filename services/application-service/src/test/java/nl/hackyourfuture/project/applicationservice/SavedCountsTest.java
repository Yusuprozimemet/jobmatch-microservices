package nl.hackyourfuture.project.applicationservice;

import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code POST /internal/saved-counts} (Day 18): {@link nl.hackyourfuture.project.backend.applications.ApplicationsDirectory#countsFor}
 * over HTTP, to service tokens only. What Day 19's client relies on: one entry per distinct id, 0
 * for nobody, {@code {}} without a query, and more than 500 distinct ids is a 400 before any
 * query.
 *
 * <p>Moved from the monolith's SavedCountsIT on Day 25. A user is a random id and a posting only
 * an id, since apps_db has neither table. The monolith's own token is job-service's here, the
 * caller in production. "Without a query" is counted on the applications JdbcClient, spied,
 * where the monolith counted statements in the harness.
 */
class SavedCountsTest extends ApplicationServiceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SavedJobCounts savedJobCounts;

    @MockitoSpyBean(name = "applicationsJdbcClient")
    private JdbcClient applicationsJdbcClient;

    private SavedJobsCalls calls;

    @BeforeEach
    void setUp() throws Exception {
        SavedJobsCalls.deleteAll();
        calls = new SavedJobsCalls(port);
    }

    @Test
    void answersWhatTheCountsAnswerInProcessWithJobServicesToken() throws Exception {
        String id1 = "saved-a";
        String id2 = "saved-b";
        String id3 = "saved-not-in-mart";

        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        SavedJobsCalls.save(user1, id1);
        SavedJobsCalls.save(user2, id1);
        SavedJobsCalls.save(user1, id2);

        String token = TestCallers.instance().token(TestCallers.JOB_SERVICE);
        var response = calls.savedCounts(
                Map.of("ids", List.of(id1, id2, id3, id3)),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().size()).isEqualTo(3);
        assertThat(response.at("/" + id1).asInt()).isEqualTo(2);
        assertThat(response.at("/" + id2).asInt()).isEqualTo(1);
        assertThat(response.at("/" + id3).asInt()).isEqualTo(0);

        Map<String, Integer> inProcess = savedJobCounts.countsFor(List.of(id1, id2, id3, id3));
        assertThat(response.body()).isEqualTo(JsonMapper.builder().build().valueToTree(inProcess));
    }

    @Test
    void answersAListedCallerToo() throws Exception {
        String id = "saved-c";
        UUID user = UUID.randomUUID();

        SavedJobsCalls.save(user, id);

        String token = TestCallers.instance().token(TestCallers.CALLER);
        var response = calls.savedCounts(
                Map.of("ids", List.of(id)),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/" + id).asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyListIsAnEmptyObjectWithoutAQuery() throws Exception {
        clearInvocations(applicationsJdbcClient);
        String token = TestCallers.instance().token(TestCallers.JOB_SERVICE);

        var response = calls.savedCounts(
                Map.of("ids", List.of()),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().size()).isZero();
        verify(applicationsJdbcClient, never()).sql(contains("saved_jobs"));
    }

    @Test
    void moreThan500DistinctIdsIs400BeforeAnyQuery() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add("saved-id-" + i);
        }
        clearInvocations(applicationsJdbcClient);

        String token = TestCallers.instance().token(TestCallers.JOB_SERVICE);

        var response = calls.savedCounts(
                Map.of("ids", ids),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(400);
        verify(applicationsJdbcClient, never()).sql(contains("saved_jobs"));
    }

    @Test
    void duplicatesDoNotCountTowardTheCap() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add("saved-dup-" + i);
        }
        // 550 entries, 500 of them distinct.
        for (int i = 0; i < 50; i++) {
            ids.add("saved-dup-" + i);
        }

        String token = TestCallers.instance().token(TestCallers.JOB_SERVICE);
        var response = calls.savedCounts(
                Map.of("ids", ids),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void aBodyWithoutIdsIs400() throws Exception {
        String token = TestCallers.instance().token(TestCallers.JOB_SERVICE);
        var response = calls.savedCounts(
                Map.of(),
                "Authorization", "Bearer " + token
        );

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void noTokenIs401() throws Exception {
        var response = calls.savedCounts(Map.of("ids", List.of("saved-d")));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void aUserTokenInTheHeaderIs401() throws Exception {
        UUID userId = UUID.randomUUID();
        String userToken = TestIdentity.instance().token(userId);
        var response = calls.savedCounts(
                Map.of("ids", List.of("saved-f")),
                "Authorization", "Bearer " + userToken
        );

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theUsersCookieIs401() throws Exception {
        UUID userId = UUID.randomUUID();
        var response = calls.savedCounts(
                Map.of("ids", List.of("saved-e")),
                "Cookie", "access_token=" + TestIdentity.instance().token(userId)
        );

        assertThat(response.status()).isEqualTo(401);
    }
}

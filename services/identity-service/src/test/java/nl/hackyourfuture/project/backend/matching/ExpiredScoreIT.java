package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.ScoreStore;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A score older than the retention window is not served (Day 22, criterion 2). The read
 * decides this, not a deletion, so it must still hold once the scores move to DynamoDB,
 * whose TTL deletes expired items only within days.
 */
class ExpiredScoreIT extends MatchingTest {

    @Test
    void asksTheModelAgainOnceTheStoredScoreIsOlderThanTheRetention() {
        TestUser user = userWithProfile();
        posting("expired-1", "Aged Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);

        authenticatedAs(user).get("/api/jobs/top-matches");

        ScoreStore.ageAll(Duration.ofDays(2));

        model().willScoreInPromptOrder(81);
        ApiResponse second = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(2);
        assertThat(second.at("/0/aiScored").asBoolean()).isTrue();
    }
}

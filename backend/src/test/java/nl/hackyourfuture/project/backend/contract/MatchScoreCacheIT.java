package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When {@code /api/jobs/top-matches} asks the model again, and when it reuses what it already
 * knows.
 *
 * <p>The cache is the reason the endpoint is affordable: scoring is the slow, paid part of the
 * request, and the answer only changes when the question does. Day 22 moves these rows from
 * Postgres into NoSQL and Day 23 cuts over to it — what is asserted here is when a call happens,
 * never where the answer was kept.
 */
class MatchScoreCacheIT extends MatchingTest {

    @Test
    void asksTheModelOnceAndReusesTheScoreOnTheSecondRequest() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);

        ApiResponse first = authenticatedAs(user).get("/api/jobs/top-matches");
        ApiResponse second = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(1);
        assertThat(first.at("/0/score").asInt()).isEqualTo(81);
        assertThat(second.at("/0/score").asInt()).isEqualTo(81);
        // Still the model's answer on the second pass, not a fallback that happens to agree.
        assertThat(second.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(second.at("/0/reason").asString()).isEqualTo(first.at("/0/reason").asString());
    }

    // The stored score belongs to a skill set, not to a user: two people with the same skills
    // are asking the same question and share the answer.
    @Test
    void reusesAScoreAcrossUsersWithTheSameSkills() {
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);
        authenticatedAs(userWithProfile()).get("/api/jobs/top-matches");

        ApiResponse second = authenticatedAs(userWithProfile()).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(1);
        assertThat(second.at("/0/score").asInt()).isEqualTo(81);
    }

    @Test
    void asksAgainWhenTheSkillSetIsDifferent() {
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);
        authenticatedAs(userWithProfile()).get("/api/jobs/top-matches");

        TestUser other = aUser().create();
        aProfile().forUser(other).preferredCity(CITY)
                .skills("java", "sql", "docker", "react", "kotlin").create();
        authenticatedAs(other).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(2);
    }

    // The order skills were picked in is not part of the question.
    @Test
    void doesNotAskAgainJustBecauseTheSkillsAreInAnotherOrder() {
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);
        authenticatedAs(userWithProfile()).get("/api/jobs/top-matches");

        TestUser other = aUser().create();
        aProfile().forUser(other).preferredCity(CITY)
                .skills("go", "react", "docker", "sql", "java").create();
        ApiResponse response = authenticatedAs(other).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(1);
        assertThat(response.at("/0/score").asInt()).isEqualTo(81);
    }

    // Only the postings without a stored score go in the next prompt, so a shortlist that
    // grows costs one question about the new rows rather than about all of them.
    @Test
    void asksOnlyAboutThePostingsItHasNoScoreFor() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(81);
        authenticatedAs(user).get("/api/jobs/top-matches");

        posting("bbb-second", "Beta Engineer", "java", "docker");
        model().willScoreInPromptOrder(42);
        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(2);
        assertThat(model().lastPrompt()).contains("Beta Engineer").doesNotContain("Alpha Engineer");
        assertThat(response.at("/0/score").asInt()).isEqualTo(81);
        assertThat(response.at("/1/score").asInt()).isEqualTo(42);
    }

    // A failed call stores nothing, so the next request is free to try again rather than
    // caching the outage.
    @Test
    void asksAgainAfterTheModelFailed() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willFail(500);
        authenticatedAs(user).get("/api/jobs/top-matches");

        model().willScoreInPromptOrder(81);
        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(2);
        assertThat(response.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(response.at("/0/score").asInt()).isEqualTo(81);
    }
}

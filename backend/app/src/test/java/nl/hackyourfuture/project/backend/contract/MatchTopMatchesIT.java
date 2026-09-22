package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/jobs/top-matches} — who may ask, what the model is told, and what the answer
 * looks like when the model is not there.
 *
 * <p>The model is a real HTTP server rather than a stubbed bean, so the request body, the
 * {@code choices[0].message.content} envelope and the JSON array inside it all go through
 * {@code MatchScorer}'s own code. Phase 4 moves this into matching-service and Day 22 moves the
 * cached scores into NoSQL; neither changes anything asserted here.
 */
class MatchTopMatchesIT extends MatchingTest {

    @Test
    void refusesAUserWhoHasNoProfileAtAll() {
        ApiResponse response = authenticatedAs(aUser().create()).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.at("/detail").asString()).contains("Fill in your profile");
    }

    // Saving a profile this short is impossible through the API - Day 03 pins that - so it is
    // written straight to the table. The production comment says older profiles can be short,
    // and this is the branch that catches them.
    @Test
    void refusesAProfileWithFewerThanFiveSkills() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity(CITY).skills("java", "sql").create();

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.at("/detail").asString()).contains("at least 5 skills");
    }

    @Test
    void ranksTheShortlistByTheModelsScoreRatherThanBySkillOverlap() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql", "docker", "react", "go");
        posting("bbb-second", "Beta Engineer", "java");

        // The first has every skill in common, the second only one - and the model disagrees.
        model().willScoreInPromptOrder(10, 90);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(postingIds(response)).containsExactly("bbb-second", "aaa-first");
        assertThat(response.at("/0/score").asInt()).isEqualTo(90);
        assertThat(response.at("/1/score").asInt()).isEqualTo(10);
    }

    @Test
    void marksTheRowsTheModelScoredAndCarriesItsReason() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(77);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(1);
        assertThat(response.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(response.at("/0/score").asInt()).isEqualTo(77);
        assertThat(response.at("/0/reason").asString()).contains("stubbed reason");
    }

    @Test
    void sendsTheProfileSkillsAndTheShortlistToTheModel() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willScoreInPromptOrder(50);

        authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().lastPrompt())
                .contains("Candidate skills:")
                .contains("java", "sql", "docker", "react", "go")
                .contains("Alpha Engineer");
    }

    // The documented promise: the model going down degrades the ranking, it does not break
    // the page.
    @Test
    void fallsBackToSkillOverlapWhenTheModelFails() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql", "docker", "react", "go");
        model().willFail(500);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(model().callCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(200);
        assertThat(postingIds(response)).containsExactly("aaa-first");
        assertThat(response.at("/0/aiScored").asBoolean()).isFalse();
        assertThat(response.at("/0/reason").isNull()).isTrue();
        // Falls back to the overlap percentage, so the list is still ordered.
        assertThat(response.at("/0/score").asInt())
                .isEqualTo(response.at("/0/matchPercent").asInt());
    }

    @Test
    void stillAnswersWhenTheModelRepliesWithSomethingUnusable() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willReplyWith("I am terribly sorry, I cannot help with that.");

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/0/aiScored").asBoolean()).isFalse();
    }

    @Test
    void returnsAnEmptyListWhenNothingIsShortlisted() {
        TestUser user = userWithProfile();

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(postingIds(response)).isEmpty();
        // Nothing to score, so the model is not called at all.
        assertThat(model().callCount()).isZero();
    }
}

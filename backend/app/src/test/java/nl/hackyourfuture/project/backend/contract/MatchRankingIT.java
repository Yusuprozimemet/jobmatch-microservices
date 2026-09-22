package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many matches come back, what the numbers on them mean, and what the model's reply may
 * look like on the way in.
 */
class MatchRankingIT extends MatchingTest {

    @Test
    void returnsAtMostTwentyFiveMatches() {
        TestUser user = userWithProfile();
        for (int i = 0; i < 30; i++) {
            posting("job-%02d".formatted(i), "Engineer %02d".formatted(i), "java", "sql");
        }
        model().willScoreInPromptOrder(new int[30]);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(postingIds(response)).hasSize(25);
    }

    // Reposts are one job: the same title at the same company collapses to a single row, so a
    // company that lists weekly cannot fill the page.
    @Test
    void keepsOnlyOneRowPerTitleAndCompany() {
        TestUser user = userWithProfile();
        aPosting().id("repost-old").title("Alpha Engineer").company("Repeater")
                .cities(CITY).skills("java", "sql").postedDaysAgo(30).create();
        aPosting().id("repost-new").title("Alpha Engineer").company("Repeater")
                .cities(CITY).skills("java", "sql").postedDaysAgo(1).create();
        model().willScoreInPromptOrder(50, 50);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(postingIds(response)).containsExactly("repost-new");
    }

    /**
     * The denominator has a floor of five, so a posting listing one skill cannot read as a
     * perfect match off a single overlap. The model is failed deliberately: {@code matchPercent}
     * is the skill-overlap figure, and letting the model score would hide it behind
     * {@code score}.
     */
    @Test
    void doesNotLetAOneSkillPostingReadAsAPerfectMatch() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java");
        model().willFail(500);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.at("/0/matchedCount").asInt()).isEqualTo(1);
        assertThat(response.at("/0/jobSkillCount").asInt()).isEqualTo(1);
        assertThat(response.at("/0/matchPercent").asInt()).isEqualTo(20);
        assertThat(response.at("/0/label").isNull()).isTrue();
    }

    // Above the floor the percentage is what it says: five of five is a hundred, and the
    // label appears at sixty.
    @Test
    void reportsTheShareOfWhatTheJobAsksForThatTheUserHas() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql", "docker", "react", "go");
        model().willFail(500);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.at("/0/matchedCount").asInt()).isEqualTo(5);
        assertThat(response.at("/0/jobSkillCount").asInt()).isEqualTo(5);
        assertThat(response.at("/0/matchPercent").asInt()).isEqualTo(100);
        assertThat(response.at("/0/ofSkills").asInt()).isEqualTo(5);
        assertThat(response.at("/0/label").asString()).isEqualTo("strong match");
    }

    // A real model wraps its JSON in prose and a code fence. MatchScorer digs the array out,
    // and that is the parsing a stubbed bean would have skipped entirely.
    @Test
    void readsTheScoresOutOfAReplyWrappedInProseAndACodeFence() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willReplyWith("""
                Sure! Here are the scores:
                ```json
                [{"id":"aaa-firs","score":64,"reason":"solid overlap"}]
                ```
                Let me know if you need anything else.""");

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(response.at("/0/score").asInt()).isEqualTo(64);
        assertThat(response.at("/0/reason").asString()).isEqualTo("solid overlap");
    }

    // A score outside 0-100 is clamped rather than passed through to the page.
    @Test
    void clampsAScoreTheModelInvented() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willReplyWith("[{\"id\":\"aaa-firs\",\"score\":9001,\"reason\":\"very good\"}]");

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.at("/0/score").asInt()).isEqualTo(100);
    }

    // An id the model made up is ignored rather than matched to something at random.
    @Test
    void ignoresAScoreForAPostingItNeverAskedAbout() {
        TestUser user = userWithProfile();
        posting("aaa-first", "Alpha Engineer", "java", "sql");
        model().willReplyWith("[{\"id\":\"invented\",\"score\":99,\"reason\":\"made up\"}]");

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(response.at("/0/aiScored").asBoolean()).isFalse();
        assertThat(response.at("/0/score").asInt())
                .isEqualTo(response.at("/0/matchPercent").asInt());
    }

    /**
     * The model is told a shortened posting id — the first eight characters — and its answer is
     * mapped back by that. Two postings whose ids agree for eight characters are one id as far
     * as the model is concerned, so only one of them can carry a score.
     *
     * <p>Harmless in production, where {@code int_postings.sql} builds ids with {@code md5(...)},
     * and the reason this is pinned rather than filed. Not harmless in the fixture: every seeded
     * id is {@code seed-00NN}, so {@code seed-0001} and {@code seed-0002} both shorten to
     * {@code seed-000}. A test written against seed postings would have scored one and silently
     * dropped the other.
     */
    @Test
    void cannotScoreTwoPostingsWhoseIdsAgreeForEightCharacters() {
        TestUser user = userWithProfile();
        posting("collide-one", "Alpha Engineer", "java", "sql");
        posting("collide-two", "Beta Engineer", "java", "sql");
        model().willScoreInPromptOrder(90, 90);

        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");

        assertThat(postingIds(response)).hasSize(2);
        int scored = 0;
        for (JsonNode match : response.json()) {
            if (match.get("aiScored").asBoolean()) {
                scored++;
            }
        }
        assertThat(scored).as("only one of two colliding ids can be scored").isEqualTo(1);
    }
}

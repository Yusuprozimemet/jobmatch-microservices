package nl.hackyourfuture.project.backend.support;

import java.time.Duration;

/**
 * Moves stored scores into the past, so a test can check that a score older than the retention
 * window is not served. It follows the scores wherever they are kept: Postgres today, DynamoDB
 * once Day 22 moves them, and the tests that call it do not change.
 */
public final class ScoreStore {

    private ScoreStore() {
    }

    /** Moves every stored score's {@code scored_at} back by {@code age}. */
    public static void ageAll(Duration age) {
        TestDatabase.jdbc().sql("UPDATE matching.job_match_scores SET scored_at = scored_at - make_interval(secs => :seconds)")
                .param("seconds", age.toSeconds())
                .update();
    }
}

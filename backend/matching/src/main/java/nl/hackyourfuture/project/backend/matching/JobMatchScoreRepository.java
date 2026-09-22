package nl.hackyourfuture.project.backend.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

// Stores what the model scored for a (skill set, posting) pair, so it's only asked once.
// One row per posting, so a mostly-known shortlist only costs the new postings.
// A stored score expires after the retention window - checked on every read, not just by
// the cleanup job, so an old score is never served even if that job is late or not running.
@Slf4j
@Repository
public class JobMatchScoreRepository {

    // Never allow less than a day, or scores would expire as fast as they're written.
    private static final int MINIMUM_RETENTION_DAYS = 1;

    private final JdbcClient jdbcClient;
    private final int retentionDays;

    public JobMatchScoreRepository(
            JdbcClient jdbcClient,
            @Value("${app.llm.score-retention-days:1}") int retentionDays
    ) {
        this.jdbcClient = jdbcClient;
        if (retentionDays < MINIMUM_RETENTION_DAYS) {
            log.warn("app.llm.score-retention-days is {}, which would expire verdicts as fast as they "
                    + "are written; using {} instead.", retentionDays, MINIMUM_RETENTION_DAYS);
        }
        this.retentionDays = Math.max(retentionDays, MINIMUM_RETENTION_DAYS);
    }

    // Returns a stored score for each posting that still has a fresh one. Missing is normal,
    // not an error - the caller just scores those postings again.
    public Map<String, MatchScorer.Score> findScores(String skillsHash, String scorerVersion,
                                                     Collection<String> postingIds) {
        if (postingIds.isEmpty()) {
            return Map.of();
        }

        return jdbcClient.sql("""
                        SELECT posting_id, score, reason
                        FROM job_match_scores
                        WHERE skills_hash = :skillsHash
                          AND scorer_version = :scorerVersion
                          AND posting_id IN (:postingIds)
                          AND scored_at > now() - make_interval(days => :retentionDays)
                        """)
                .param("skillsHash", skillsHash)
                .param("scorerVersion", scorerVersion)
                .param("postingIds", postingIds)
                .param("retentionDays", retentionDays)
                .query((rs, _) -> Map.entry(
                        rs.getString("posting_id"),
                        new MatchScorer.Score(rs.getInt("score"), rs.getString("reason"))))
                // Use list(), not stream() - a stream would keep the DB connection open until
                // closed, and leaked one every call. The row count here is always small anyway.
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Saves freshly scored postings. Never throws - if the save fails, the user still gets
    // their ranking, and that posting just gets scored again next time.
    // ON CONFLICT overwrites, so a rescore replaces the old row instead of leaving it behind.
    public void saveScores(String skillsHash, String scorerVersion,
                           Map<String, MatchScorer.Score> scores) {
        if (scores.isEmpty()) {
            return;
        }
        try {
            // One insert per posting - at most 40, so this is cheap.
            scores.forEach((postingId, score) -> jdbcClient.sql("""
                            INSERT INTO job_match_scores
                                (skills_hash, posting_id, scorer_version, score, reason)
                            VALUES (:skillsHash, :postingId, :scorerVersion, :score, :reason)
                            ON CONFLICT (skills_hash, posting_id, scorer_version) DO UPDATE
                                SET score = excluded.score,
                                    reason = excluded.reason,
                                    scored_at = now()
                            """)
                    .param("skillsHash", skillsHash)
                    .param("postingId", postingId)
                    .param("scorerVersion", scorerVersion)
                    .param("score", score.value())
                    .param("reason", score.reason())
                    .update());
        } catch (DataAccessException e) {
            // Log the full exception, not just its message, so the real cause is visible later.
            log.warn("Could not store {} job match scores, they will be rescored later", scores.size(), e);
        }
    }

    // Deletes expired scores to free up space - findScores already skips them either way.
    public int deleteExpired() {
        return jdbcClient.sql("""
                        DELETE FROM job_match_scores
                        WHERE scored_at <= now() - make_interval(days => :retentionDays)
                        """)
                .param("retentionDays", retentionDays)
                .update();
    }
}

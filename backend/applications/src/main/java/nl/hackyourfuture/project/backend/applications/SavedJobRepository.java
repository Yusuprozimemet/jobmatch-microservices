package nl.hackyourfuture.project.backend.applications;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class SavedJobRepository {

    private final JdbcClient jdbcClient;

    public SavedJobRepository(@Qualifier("applicationsJdbcClient") JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Insert a new saved job with default 'SAVED' state
    public void saveJob(UUID userId, String postingId) {
        String sql = "INSERT INTO saved_jobs (user_id, posting_id, job_state) VALUES (?, ?, 'SAVED')";
        jdbcClient.sql(sql)
                .params(userId, postingId)
                .update();
    }

    // Check if the user has already saved this specific posting
    public boolean isJobSaved(UUID userId, String postingId) {
        String sql = "SELECT COUNT(*) FROM saved_jobs WHERE user_id = ? AND posting_id = ?";
        Integer count = jdbcClient.sql(sql)
                .params(userId, postingId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    // Every saved row for a user, unordered. The order comes from the postings, which this
    // module does not hold - SavedJobService sorts and pages once the details are in.
    public List<SavedRow> findSavedJobs(UUID userId) {
        return jdbcClient.sql("SELECT posting_id, job_state FROM saved_jobs WHERE user_id = ?")
                .param(userId)
                .query((rs, rowNum) -> new SavedRow(
                        rs.getString("posting_id"), JobState.valueOf(rs.getString("job_state"))))
                .list();
    }

    public record SavedRow(String postingId, JobState jobState) {
    }

    // Update the job state (e.g. SAVED -> APPLIED) and return true if successful
    public boolean updateJobState(UUID userId, String postingId, JobState newState) {
        String sql = "UPDATE saved_jobs SET job_state = ?::job_state WHERE user_id = ? AND posting_id = ?";
        int rowsAffected = jdbcClient.sql(sql)
                .params(newState.name(), userId, postingId)
                .update();
        return rowsAffected > 0;
    }

    // Delete a saved job record and return true if a row was deleted
    public boolean removeSavedJob(UUID userId, String postingId) {
        String sql = "DELETE FROM saved_jobs WHERE user_id = ? AND posting_id = ?";
        int rowsAffected = jdbcClient.sql(sql)
                .params(userId, postingId)
                .update();
        return rowsAffected > 0;
    }

    // Fetch count of saved jobs grouped by their state for dashboard stats
    public Map<JobState, Integer> getJobStats(UUID userId) {
        String sql = "SELECT job_state, COUNT(*) as count FROM saved_jobs WHERE user_id = ? GROUP BY job_state";
        List<Map<String, Object>> results = jdbcClient.sql(sql)
                .param(userId)
                .query()
                .listOfRows();

        return results.stream().collect(Collectors.toMap(
                row -> JobState.valueOf((String) row.get("job_state")),
                row -> ((Number) row.get("count")).intValue()
        ));
    }
}
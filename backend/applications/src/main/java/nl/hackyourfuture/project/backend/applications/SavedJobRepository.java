package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.shared.mart.MartSkills;
import nl.hackyourfuture.project.backend.applications.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class SavedJobRepository {

    private final JdbcClient jdbcClient;

    public SavedJobRepository(JdbcClient jdbcClient) {
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

    // Helper method to count total saved jobs for a specific user
    private long countSavedJobs(UUID userId) {
        String sql = "SELECT COUNT(*) FROM saved_jobs WHERE user_id = ?";
        Long count = jdbcClient.sql(sql)
                .param(userId)
                .query(Long.class)
                .single();
        return count != null ? count : 0;
    }

    // Fetch the list of saved jobs with posting details for a user
    public PageResponse<SavedJobResponse> getSavedJobsWithDetails(UUID userId, int page, int size) {
        long offset = (long) page * size;
        long totalElements = countSavedJobs(userId);
        String sql = """
                SELECT
                    sj.posting_id,
                    sj.job_state,
                    p.title,
                    p.company_name,
                    p.location,
                    p.work_mode,
                    p.is_remote,
                    p.skills,
                    p.employment_type,
                    p.posted_date,
                    p.source,
                    p.category,
                    p.freshness_class,
                    p.age_days
                FROM saved_jobs sj
                LEFT JOIN analytics.fct_postings p ON sj.posting_id = p.posting_id
                WHERE sj.user_id = ?
                ORDER BY p.posted_date DESC NULLS LAST, sj.posting_id ASC
                LIMIT ? OFFSET ?
                """;

        // Store query results in 'content' variable
        List<SavedJobResponse> content = jdbcClient.sql(sql)
                .params(userId, size, offset)
                .query((rs, rowNum) -> {
                    List<String> skillsList = MartSkills.parse(rs.getString("skills"));

                    return new SavedJobResponse(
                            rs.getString("posting_id"),
                            JobState.valueOf(rs.getString("job_state")),
                            rs.getString("title"),
                            rs.getString("company_name"),
                            rs.getString("location"),
                            rs.getString("work_mode"),
                            rs.getObject("is_remote") != null ? rs.getBoolean("is_remote") : null,
                            skillsList,
                            rs.getString("employment_type"),
                            rs.getObject("posted_date", LocalDate.class),
                            rs.getString("source"),
                            rs.getString("category"),
                            rs.getString("freshness_class"),
                            rs.getObject("age_days") != null ? rs.getInt("age_days") : null
                    );
                })
                .list();
        // Return wrapped PageResponse
        return PageResponse.of(content, page, size, totalElements);
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
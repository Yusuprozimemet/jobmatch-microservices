package nl.hackyourfuture.project.backend.jobs;

import nl.hackyourfuture.project.backend.shared.jobs.PostingLookup;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
import nl.hackyourfuture.project.backend.shared.mart.MartSkills;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What other modules are allowed to ask {@code jobs} about postings.
 *
 * <p>Package-private for the same reason as {@code IdentityDirectory} and
 * {@code ApplicationsDirectory}: nothing outside {@code jobs} names this class, only the
 * interfaces it implements.
 */
@Component
class JobsDirectory implements PostingLookup {

    private final JdbcClient jdbcClient;

    JobsDirectory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * One query for the whole collection. Reads the columns the saved-jobs join read -
     * {@code location} and {@code skills} straight off {@code fct_postings} - not the ones job
     * search reads, because Day 04 pinned what saved jobs show.
     */
    @Override
    public Map<String, PostingSummary> byIds(Collection<String> postingIds) {
        Map<String, PostingSummary> summaries = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>(postingIds);
        // An empty collection would expand to IN (), which Postgres rejects.
        if (ids.isEmpty()) {
            return summaries;
        }

        jdbcClient.sql("""
                        SELECT posting_id, title, company_name, location, work_mode, is_remote, skills,
                               employment_type, posted_date, source, category, freshness_class, age_days
                        FROM analytics.fct_postings
                        WHERE posting_id IN (:postingIds)
                        """)
                .param("postingIds", ids)
                .query((RowCallbackHandler) rs -> summaries.put(rs.getString("posting_id"), new PostingSummary(
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("location"),
                        rs.getString("work_mode"),
                        rs.getObject("is_remote") != null ? rs.getBoolean("is_remote") : null,
                        MartSkills.parse(rs.getString("skills")),
                        rs.getString("employment_type"),
                        rs.getObject("posted_date", LocalDate.class),
                        rs.getString("source"),
                        rs.getString("category"),
                        rs.getString("freshness_class"),
                        rs.getObject("age_days") != null ? rs.getInt("age_days") : null)));
        return summaries;
    }
}

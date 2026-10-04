package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What other modules are allowed to know about saved jobs.
 *
 * <p>Package-private for the same reason as identity's {@code IdentityDirectory}: nothing
 * outside {@code applications} names this class, only the interface it implements.
 */
@Component
class ApplicationsDirectory implements SavedJobCounts {

    private final JdbcClient jdbcClient;

    ApplicationsDirectory(@Qualifier("applicationsJdbcClient") JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * One query for the whole page. DISTINCT user_id keeps the number meaning what the
     * subquery in {@code jobs} meant by it.
     */
    @Override
    public Map<String, Integer> countsFor(Collection<String> postingIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        postingIds.forEach(id -> counts.put(id, 0));
        // An empty collection would expand to IN (), which Postgres rejects - and an empty
        // page has nothing to count anyway.
        if (counts.isEmpty()) {
            return counts;
        }

        jdbcClient.sql("""
                        SELECT posting_id, COUNT(DISTINCT user_id) AS saved_count
                        FROM saved_jobs
                        WHERE posting_id IN (:postingIds)
                        GROUP BY posting_id
                        """)
                .param("postingIds", counts.keySet())
                .query((RowCallbackHandler) rs ->
                        counts.put(rs.getString("posting_id"), rs.getInt("saved_count")));
        return counts;
    }
}

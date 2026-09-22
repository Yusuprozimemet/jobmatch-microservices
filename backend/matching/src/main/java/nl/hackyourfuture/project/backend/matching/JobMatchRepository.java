package nl.hackyourfuture.project.backend.matching;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

// Quick first pass: shortlists postings by exact skill match. The model handles synonyms later.
@Repository
@RequiredArgsConstructor
public class JobMatchRepository {

    private final JdbcClient jdbcClient;

    // Four steps: 1) candidate keeps only usable open jobs, 2) scored counts skill matches,
    // 3) deduplicated removes duplicate reposts, 4) the final select returns a ranked shortlist.
    // city: optional, from GET /api/jobs/filters. skills: lowercase, non-empty.
    public List<JobMatchRow> findTopMatches(String city, List<String> skills, int limit) {
        StringBuilder sql = new StringBuilder("""
                -- 1) candidate: keep only usable open jobs.
                WITH candidate AS (
                    -- Take all postings from analytics.fct_postings.
                    SELECT posting_id, title, company_name, location, category, skills, posted_date
                    FROM analytics.fct_postings
                    -- Keep only status = 'open' and closed_at is null.
                    WHERE status = 'open'
                      AND closed_at IS NULL
                      -- Keep only postings that have skills listed.
                      AND skills IS NOT NULL
                      AND skills <> ''
                      -- Skip any row with broken JSON skills, instead of crashing the query.
                      AND pg_input_is_valid(skills, 'jsonb')
                """);

        // If a city was given, also keep only jobs in that city (otherwise keep every city).
        if (city != null && !city.isBlank()) {
            sql.append("""
                          AND EXISTS (
                              SELECT 1 FROM analytics.fct_postings_cities c
                              WHERE c.posting_id = fct_postings.posting_id
                                AND lower(c.city) = lower(:city)
                          )
                    """);
        }

        sql.append("""
                -- 2) scored: for every candidate job, count how its skills match the user's.
                ), scored AS (
                    SELECT
                        posting_id,
                        title,
                        company_name,
                        location,
                        category,
                        posted_date,

                        -- Count how many skills the job lists -> job_skill_count.
                        jsonb_array_length(skills::jsonb) AS job_skill_count,

                        -- Find which of the job's skills also appear in the user's skills
                        -- (an empty list if none do) -> matched_skills.
                        (SELECT coalesce(array_agg(s), '{}')
                         FROM jsonb_array_elements_text(skills::jsonb) s
                         WHERE lower(s) IN (:skills)) AS matched_skills,

                        -- Keep the full list of job skills for later display -> job_skills.
                        (SELECT array_agg(s)
                         FROM jsonb_array_elements_text(skills::jsonb) s) AS job_skills

                    FROM candidate

                -- 3) deduplicated: remove duplicate reposts of the same job.
                ), deduplicated AS (
                    SELECT scored.*,
                           -- Group jobs with the same title + company, and inside each group
                           -- rank them by most matched skills, then newest posted_date.
                           row_number() OVER (
                               PARTITION BY lower(title), lower(coalesce(company_name, ''))
                               ORDER BY cardinality(matched_skills) DESC,
                                        posted_date DESC NULLS LAST,
                                        posting_id
                           ) AS repost_rank
                    FROM scored
                )
                -- 4) final result: keep only the best job per group (repost_rank = 1),
                -- sort by most matched skills then newest, and return the top `limit` rows.
                SELECT posting_id, title, company_name, location, category, posted_date,
                       job_skill_count, matched_skills, job_skills
                FROM deduplicated
                WHERE repost_rank = 1
                ORDER BY cardinality(matched_skills) DESC,
                         posted_date DESC NULLS LAST,
                         posting_id
                LIMIT :limit
                """);

        var statement = jdbcClient.sql(sql.toString())
                .param("skills", skills)
                .param("limit", limit);

        if (city != null && !city.isBlank()) {
            statement.param("city", city);
        }

        return statement.query((rs, rowNum) -> new JobMatchRow(
                rs.getString("posting_id"),
                rs.getString("title"),
                rs.getString("company_name"),
                rs.getString("location"),
                rs.getString("category"),
                rs.getObject("posted_date", LocalDate.class),
                readArray(rs, "job_skills"),
                readArray(rs, "matched_skills"),
                rs.getInt("job_skill_count")
        )).list();
    }


    //Converts a Postgres array column into a Java list of strings, or an empty list if it's null.
    private static List<String> readArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return Collections.emptyList();
        }
        return List.of((String[]) array.getArray());
    }

    // One shortlisted posting, before the model scores it.
    public record JobMatchRow(
            String postingId,
            String title,
            String company,
            String location,
            String category,
            LocalDate postedDate,
            List<String> jobSkills,
            List<String> matchedSkills,
            int jobSkillCount
    ) {
        public int matchedCount() {
            return matchedSkills.size();
        }
    }
}

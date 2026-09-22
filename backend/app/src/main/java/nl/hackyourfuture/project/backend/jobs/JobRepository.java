package nl.hackyourfuture.project.backend.jobs;

import nl.hackyourfuture.project.backend.jobs.dto.JobDetailResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobFiltersResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobSearchResponse;
import nl.hackyourfuture.project.backend.jobs.dto.PageResponse;
import nl.hackyourfuture.project.backend.mart.MartSkills;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {

    // Caps an unfiltered search from returning the whole mart. posting_id breaks ties so
    // the cut-off is stable between calls.
    private static final int MAX_SEARCH_RESULTS = 200;

    // fct_postings_cities mixes in countries and provinces, so these are excluded from every
    // city query: the dropdown, the location filter, and the city shown on a posting. Cities
    // that share a name with a province (Utrecht, Groningen) stay in. Re-derive when new bad
    // values show up; the real fix belongs upstream, in the city column itself.
    private static final List<String> NON_CITY_LOCATIONS = List.of(
            // Countries - "netherlands" is the most common bad value here.
            "netherlands", "nederland", "the netherlands", "holland",
            "australia", "belgium", "canada", "denmark", "djibouti", "france", "germany",
            "india", "ireland", "jamaica", "liberia", "mozambique", "poland", "portugal",
            "spain", "sweden", "switzerland", "tonga",
            "uk", "united kingdom", "united states", "usa",
            // Provinces.
            "north holland", "noord-holland", "south holland", "zuid-holland",
            "noord-brabant", "north brabant", "gelderland", "overijssel", "drenthe", "flevoland",
            // "Remote" written into the city field instead of using is_remote.
            "netherlands - remote", "netherlands remote", "remote - netherlands",
            "remote in europe", "remote netherlands", "remote-netherlands");

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResponse<JobSearchResponse>
    searchJobs(String category, String workMode, String location, String q, int page, int size) {
        size = Math.min(size, MAX_SEARCH_RESULTS);
        long offset = (long) page * size; // long avoids int overflow on large pages
        long totalElements = countJobs(category, workMode, location, q);
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.posting_id,
                    f.title,
                    f.company_name,
                    COALESCE((SELECT string_agg(DISTINCT initcap(sub_c.city), ', ' ORDER BY initcap(sub_c.city))
                        FROM analytics.fct_postings_cities sub_c
                        WHERE sub_c.posting_id = f.posting_id
                          AND sub_c.city IS NOT NULL
                          AND sub_c.city <> ''
                          AND lower(sub_c.city) NOT IN (:excluded)), '') AS location,
                    f.work_mode,
                    f.is_remote,
                    COALESCE((SELECT json_agg(DISTINCT s.skill ORDER BY s.skill)::text
                    FROM analytics.fct_postings_skills s
                    WHERE s.posting_id = f.posting_id), '[]') AS skills,
                    f.employment_type,
                    f.posted_date,
                    f.source,
                    f.category,
                    f.freshness_class,
                    f.age_days,
                    (SELECT COUNT(DISTINCT user_id)
                    FROM saved_jobs
                    WHERE posting_id = f.posting_id) AS saved_count
                FROM analytics.fct_postings f
                WHERE 1=1
                """);

        if (category != null && !category.isBlank()) {
            sql.append(" AND f.category = :category");
        }
        if (workMode != null && !workMode.isBlank()) {
            sql.append(" AND f.work_mode = :workMode");
        }
        if (location != null && !location.isBlank()) {
            // Matches the normalised city table, not the free-text location column, and by
            // equality rather than substring: the value is already a whole city name from
            // the dropdown, and '%Ede%' also matched Enschede, Medemblik and Sweden.
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM analytics.fct_postings_cities sub_c
                         WHERE sub_c.posting_id = f.posting_id
                           AND lower(sub_c.city) = lower(:location)
                           AND lower(sub_c.city) NOT IN (:excluded)
                     )
                    """);
        }
        if (q != null && !q.isBlank()) {
            sql.append("""
                     AND (
                         f.title ILIKE :q
                         OR f.company_name ILIKE :q
                         OR EXISTS (
                            SELECT 1 FROM analytics.fct_postings_cities sub_c
                            WHERE sub_c.posting_id = f.posting_id AND sub_c.city ILIKE :q
                            )
                         OR EXISTS (
                             SELECT 1 FROM analytics.fct_postings_skills sub_s
                             WHERE sub_s.posting_id = f.posting_id AND sub_s.skill ILIKE :q
                             )
                     )
                    """);
        }

        sql.append(" ORDER BY f.posted_date DESC NULLS LAST, f.posting_id LIMIT :limit OFFSET :offset");

        var statement = jdbcClient.sql(sql.toString())
                .param("limit", size)
                .param("offset", offset)
                .param("excluded", NON_CITY_LOCATIONS);

        if (category != null && !category.isBlank()) {
            statement.param("category", category);
        }
        if (workMode != null && !workMode.isBlank()) {
            statement.param("workMode", workMode);
        }
        if (location != null && !location.isBlank()) {
            statement.param("location", location);
        }
        if (q != null && !q.isBlank()) {
            statement.param("q", "%" + q + "%");
        }

        List<JobSearchResponse> content = statement.query((rs, rowNum) -> {
            List<String> skillsList = MartSkills.parse(rs.getString("skills"));
            return new JobSearchResponse(
                    rs.getString("posting_id"),
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
                    rs.getObject("age_days") != null ? rs.getInt("age_days") : null,
                    rs.getInt("saved_count")
            );
        }).list();

        return PageResponse.of(content, page, size, totalElements);
    }

    // Same filters as searchJobs, just a count.
    private long countJobs(String category, String workMode, String location, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM analytics.fct_postings f
                WHERE 1=1
                """);

        if (category != null && !category.isBlank()) {
            sql.append(" AND f.category = :category");
        }
        if (workMode != null && !workMode.isBlank()) {
            sql.append(" AND f.work_mode = :workMode");
        }
        if (location != null && !location.isBlank()) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM analytics.fct_postings_cities sub_c
                         WHERE sub_c.posting_id = f.posting_id
                           AND lower(sub_c.city) = lower(:location)
                           AND lower(sub_c.city) NOT IN (:excluded)
                     )
                    """);
        }
        if (q != null && !q.isBlank()) {
            sql.append("""
                     AND (
                         f.title ILIKE :q
                         OR f.company_name ILIKE :q
                         OR EXISTS (
                            SELECT 1 FROM analytics.fct_postings_cities sub_c
                            WHERE sub_c.posting_id = f.posting_id AND sub_c.city ILIKE :q
                            )
                         OR EXISTS (
                             SELECT 1 FROM analytics.fct_postings_skills sub_s
                             WHERE sub_s.posting_id = f.posting_id AND sub_s.skill ILIKE :q
                             )
                     )
                    """);
        }

        var statement = jdbcClient.sql(sql.toString())
                .param("excluded", NON_CITY_LOCATIONS);

        if (category != null && !category.isBlank()) {
            statement.param("category", category);
        }
        if (workMode != null && !workMode.isBlank()) {
            statement.param("workMode", workMode);
        }
        if (location != null && !location.isBlank()) {
            statement.param("location", location);
        }
        if (q != null && !q.isBlank()) {
            statement.param("q", "%" + q + "%");
        }

        return statement.query(Long.class).single();
    }

    public Optional<JobDetailResponse> getJobById(String postingId) {
        String sql = """
                SELECT
                    f.posting_id,
                    f.title,
                    f.company_name,
                    f.work_mode,
                    f.is_remote,
                    f.employment_type,
                    f.posted_date,
                    f.source,
                    f.category,
                    f.freshness_class,
                    f.age_days,
                    f.description,
                    f.experience_level,
                    f.education_level,
                    f.salary_min,
                    f.salary_max,
                    f.salary_currency,
                    f.salary_period,
                    f.source_url,
                    f.status,
                    COALESCE((SELECT string_agg(DISTINCT initcap(sub_c.city), ', ' ORDER BY initcap(sub_c.city))
                        FROM analytics.fct_postings_cities sub_c
                        WHERE sub_c.posting_id = f.posting_id
                          AND sub_c.city IS NOT NULL
                          AND sub_c.city <> ''
                          AND lower(sub_c.city) NOT IN (:excluded)), '') AS location,
                    COALESCE((SELECT json_agg(DISTINCT s.skill ORDER BY s.skill)::text
                    FROM analytics.fct_postings_skills s
                    WHERE s.posting_id = f.posting_id), '[]') AS skills,
                    (SELECT COUNT(DISTINCT user_id)
                    FROM saved_jobs
                    WHERE posting_id = f.posting_id) AS saved_count
                FROM analytics.fct_postings f
                WHERE f.posting_id = :postingId
                """;

        return jdbcClient.sql(sql)
                .param("postingId", postingId)
                .param("excluded", NON_CITY_LOCATIONS)
                .query((rs, rowNum) -> {
                    List<String> skillsList = MartSkills.parse(rs.getString("skills"));
                    return new JobDetailResponse(
                            rs.getString("posting_id"),
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
                            rs.getObject("age_days") != null ? rs.getInt("age_days") : null,
                            rs.getString("description"),
                            rs.getString("experience_level"),
                            rs.getString("education_level"),
                            rs.getObject("salary_min") != null ? rs.getDouble("salary_min") : null,
                            rs.getObject("salary_max") != null ? rs.getDouble("salary_max") : null,
                            rs.getString("salary_currency"),
                            rs.getString("salary_period"),
                            rs.getString("source_url"),
                            rs.getString("status"),
                            rs.getInt("saved_count")
                    );
                })
                .optional();
    }

    // Categories come from the mart as-is, snake_cased (data_engineering), because the value
    // round-trips into searchJobs as a filter - the frontend formats it for display.
    public JobFiltersResponse getAvailableFilters() {
        String sql = """
                SELECT
                    COALESCE((
                        SELECT array_agg(DISTINCT category)
                        FROM analytics.fct_postings
                        WHERE category IS NOT NULL), '{}') AS categories,
                    COALESCE((
                        SELECT array_agg(DISTINCT work_mode)
                        FROM analytics.fct_postings
                        WHERE work_mode IS NOT NULL), '{}') AS work_modes,
                    COALESCE((
                        SELECT array_agg(DISTINCT experience_level)
                        FROM analytics.fct_postings
                        WHERE experience_level IS NOT NULL), '{}') AS experience_levels,
                    COALESCE((
                        SELECT array_agg(DISTINCT employment_type)
                        FROM analytics.fct_postings
                        WHERE employment_type IS NOT NULL), '{}') AS employment_types
                FROM (VALUES (1)) AS t
                """;

        // Run before the query below, not inside its mapper - nesting it there held two
        // connections per request and could deadlock the pool under load.
        List<String> cities = getCityOptions();

        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> new JobFiltersResponse(
                        cities,
                        parseStringArray(rs.getObject("categories")),
                        parseStringArray(rs.getObject("work_modes")),
                        parseStringArray(rs.getObject("experience_levels")),
                        parseStringArray(rs.getObject("employment_types"))
                ))
                .single();
    }

    // Uses the normalised city table, not the free-text location column, which had hundreds
    // of near-duplicates ("Amsterdam", "Amsterdam, Netherlands"). initcap is display only -
    // the value round-trips into searchJobs, compared case-insensitively.
    private List<String> getCityOptions() {
        String sql = """
                SELECT initcap(city) AS city
                FROM analytics.fct_postings_cities
                WHERE city IS NOT NULL
                  AND city <> ''
                  AND lower(city) NOT IN (:excluded)
                GROUP BY city
                ORDER BY city
                """;

        return jdbcClient.sql(sql)
                .param("excluded", NON_CITY_LOCATIONS)
                .query(String.class)
                .list();
    }

    private List<String> parseStringArray(Object arrayObj) {
            if (arrayObj == null) {
                return Collections.emptyList();
            }
            if (arrayObj instanceof java.sql.Array sqlArray) {
                try {
                    Object arrayData = sqlArray.getArray();
                    if (arrayData instanceof Object[] objArr) {
                        return Arrays.stream(objArr)
                                .filter(item -> item != null)
                                .map(Object::toString)
                                .filter(s -> !s.isBlank())
                                .distinct()
                                .sorted()
                                .toList();
                    }
                } catch (Exception e) {
                    return Collections.emptyList();
                }
            }
            if (arrayObj instanceof Object[] arr) {
                return Arrays.stream(arr)
                        .map(Object::toString)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
            }
            return Collections.emptyList();
        }
}

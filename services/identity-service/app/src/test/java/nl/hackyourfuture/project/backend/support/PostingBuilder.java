package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds a mart posting on top of the seeded baseline. Start one with {@code aPosting()}.
 *
 * <p>Writes all three mart tables, because the backend reads the fact table for a posting's
 * fields and the two bridge tables for its cities and skills. A posting inserted into only
 * {@code fct_postings} would be invisible to a city filter and would score zero on matching.
 */
public final class PostingBuilder {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final JdbcClient jdbc;

    private String postingId;
    private String title = "Backend Developer";
    private String company = "Test Company";
    private List<String> cities = List.of("amsterdam");
    private List<String> skills = List.of("java", "sql");
    private String category = "software_engineering";
    private String workMode = "onsite";
    private String employmentType = "full_time";
    private String experienceLevel = "medior";
    private String educationLevel = "bachelor";
    private Double salaryMin = 4000.0;
    private Double salaryMax = 5500.0;
    private int postedDaysAgo = 1;
    private boolean closed;
    private String description = "A posting created by a test.";

    PostingBuilder(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.postingId = "test-" + COUNTER.incrementAndGet();
    }

    public PostingBuilder id(String value) {
        this.postingId = value;
        return this;
    }

    public PostingBuilder title(String value) {
        this.title = value;
        return this;
    }

    public PostingBuilder company(String value) {
        this.company = value;
        return this;
    }

    /** Lowercase, like the city bridge the pipeline builds. */
    public PostingBuilder cities(String... values) {
        this.cities = lowercased(values);
        return this;
    }

    /** Lowercase, like the skills the pipeline extracts. */
    public PostingBuilder skills(String... values) {
        this.skills = lowercased(values);
        return this;
    }

    public PostingBuilder category(String value) {
        this.category = value;
        return this;
    }

    public PostingBuilder workMode(String value) {
        this.workMode = value;
        return this;
    }

    public PostingBuilder employmentType(String value) {
        this.employmentType = value;
        return this;
    }

    public PostingBuilder experienceLevel(String value) {
        this.experienceLevel = value;
        return this;
    }

    public PostingBuilder educationLevel(String value) {
        this.educationLevel = value;
        return this;
    }

    public PostingBuilder salary(Double min, Double max) {
        this.salaryMin = min;
        this.salaryMax = max;
        return this;
    }

    public PostingBuilder postedDaysAgo(int value) {
        this.postedDaysAgo = value;
        return this;
    }

    /** Closed postings are excluded from matching, so a test needs to be able to make one. */
    public PostingBuilder closed() {
        this.closed = true;
        return this;
    }

    public PostingBuilder description(String value) {
        this.description = value;
        return this;
    }

    public TestPosting create() {
        jdbc.sql("""
                        INSERT INTO analytics.fct_postings (
                            posting_id, source, source_job_id, title, company_name, location,
                            countries, regions, cities, has_location_data, work_mode, is_remote,
                            skills, skill_count, experience_level, education_level, employment_type,
                            salary_min, salary_max, salary_currency, salary_period, category,
                            description, posted_at, posted_date, updated_at, last_seen_at, closed_at,
                            status, freshness_class, age_days, repost_count, fake_freshness,
                            source_url, ingest_date, ingested_at)
                        VALUES (
                            :postingId, 'test', 'test-source-' || :postingId, :title, :company, :location,
                            '["netherlands"]', '[]', :cities, :hasLocation, :workMode, :isRemote,
                            :skills, :skillCount, :experienceLevel, :educationLevel, :employmentType,
                            :salaryMin, :salaryMax, 'EUR', 'month', :category,
                            :description,
                            now() - make_interval(days => (:ageDays)::int), current_date - (:ageDays)::int,
                            now() - make_interval(days => (:ageDays)::int), now(),
                            CASE WHEN :closed THEN now() END,
                            :status, :freshness, :ageDays, 0, false,
                            'https://example.test/jobs/' || :postingId, current_date, now())
                        """)
                .param("postingId", postingId)
                .param("title", title)
                .param("company", company)
                .param("location", location())
                .param("cities", json(cities))
                .param("hasLocation", !cities.isEmpty())
                .param("workMode", workMode)
                .param("isRemote", "remote".equals(workMode))
                .param("skills", json(skills))
                .param("skillCount", skills.size())
                .param("experienceLevel", experienceLevel)
                .param("educationLevel", educationLevel)
                .param("employmentType", employmentType)
                .param("salaryMin", salaryMin)
                .param("salaryMax", salaryMax)
                .param("category", category)
                .param("description", description)
                .param("ageDays", postedDaysAgo)
                .param("closed", closed)
                .param("status", closed ? "closed" : "open")
                .param("freshness", freshnessClass())
                .update();

        for (String city : cities) {
            insertBridgeRow("analytics.fct_postings_cities", "city", city);
        }
        for (String skill : skills) {
            insertBridgeRow("analytics.fct_postings_skills", "skill", skill);
        }

        return new TestPosting(postingId, title, company, cities, skills);
    }

    private void insertBridgeRow(String table, String valueColumn, String value) {
        jdbc.sql("""
                        INSERT INTO %s (posting_id, %s, source, title, posted_at, posted_date)
                        SELECT posting_id, :value, source, title, posted_at, posted_date
                        FROM analytics.fct_postings WHERE posting_id = :postingId
                        """.formatted(table, valueColumn))
                .param("value", value)
                .param("postingId", postingId)
                .update();
    }

    // The free-text location column the pipeline passes through for display.
    private String location() {
        return cities.stream()
                .map(city -> city.substring(0, 1).toUpperCase(Locale.ROOT) + city.substring(1))
                .collect(Collectors.joining(", "));
    }

    private String freshnessClass() {
        if (postedDaysAgo <= 7) {
            return "fresh";
        }
        return postedDaysAgo <= 30 ? "recent" : "stale";
    }

    private static List<String> lowercased(String... values) {
        return List.of(values).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    // The mart stores these as text holding a JSON array, not as a Postgres array.
    private static String json(List<String> values) {
        return JSON.writeValueAsString(values);
    }
}

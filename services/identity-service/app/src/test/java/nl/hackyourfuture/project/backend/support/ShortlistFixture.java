package nl.hackyourfuture.project.backend.support;

import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Postings whose shortlist order each ordering rule decides at least once, for
 * {@code postings/ShortlistOrderIT} in process and Day 18's shortlist endpoint over HTTP: both
 * must return {@link #EXPECTED}. The rules, in {@code JobsDirectory}'s SQL: more matched skills
 * first, then newest ({@code posted_date DESC NULLS LAST}), then {@code posting_id}; one row per
 * title and company, case ignored; only the city asked for; at most {@code limit}.
 *
 * <p>Its own city, which no {@code seed-*} posting has, keeps {@code TestDatabase.reset()}'s
 * seeds out of the list.
 */
public final class ShortlistFixture {

    public static final String CITY = "orderville";
    public static final List<String> SKILLS = List.of("java", "sql", "go");
    public static final int LIMIT = 8;
    public static final List<String> EXPECTED = List.of(
            "order-a", "order-b", "order-r1", "order-c", "order-d", "order-e1", "order-e2", "order-f"
    );

    private ShortlistFixture() {
    }

    /**
     * The whole answer {@link #EXPECTED} stands for, row by row. {@code today} is the database's
     * {@code current_date}, which the postings are dated from: the JVM's date differs from it for
     * part of every day, the container running in UTC.
     */
    public static List<ShortlistedPosting> expectedRows(LocalDate today) {
        return List.of(
                new ShortlistedPosting("order-a", "Backend Developer", "Company A", "Orderville",
                        "software_engineering", today.minusDays(5), List.of("java", "sql", "go"),
                        List.of("java", "sql", "go"), 3),
                new ShortlistedPosting("order-b", "Software Engineer", "Company B", "Orderville",
                        "software_engineering", today.minusDays(1), List.of("java", "sql"),
                        List.of("java", "sql"), 2),
                new ShortlistedPosting("order-r1", "Backend Developer", "Acme Repost", "Orderville",
                        "software_engineering", today.minusDays(2), List.of("java", "sql"),
                        List.of("java", "sql"), 2),
                new ShortlistedPosting("order-c", "Java Developer", "Company C", "Orderville",
                        "software_engineering", today.minusDays(3), List.of("java", "sql"),
                        List.of("java", "sql"), 2),
                new ShortlistedPosting("order-d", "Database Engineer", "Company D", "Orderville",
                        "software_engineering", null, List.of("java", "sql"),
                        List.of("java", "sql"), 2),
                new ShortlistedPosting("order-e1", "Frontend Engineer", "Company E1", "Orderville",
                        "software_engineering", today.minusDays(6), List.of("java"),
                        List.of("java"), 1),
                new ShortlistedPosting("order-e2", "Fullstack Developer", "Company E2", "Orderville",
                        "software_engineering", today.minusDays(6), List.of("java"),
                        List.of("java"), 1),
                new ShortlistedPosting("order-f", "Systems Engineer", "Company F", "Orderville",
                        "software_engineering", today.minusDays(10), List.of("java"),
                        List.of("java"), 1)
        );
    }

    /** Inserts the postings; {@link #EXPECTED} is the list they must come back as. */
    public static void create(JdbcClient jdbc) {
        // Three matches: first, although four postings are newer.
        new PostingBuilder(jdbc).id("order-a")
                .title("Backend Developer").company("Company A")
                .cities(CITY).skills("java", "sql", "go").postedDaysAgo(5).create();

        // Two matches, newest to oldest: b, r1, c; then d, whose date is null.
        new PostingBuilder(jdbc).id("order-b")
                .title("Software Engineer").company("Company B")
                .cities(CITY).skills("java", "sql").postedDaysAgo(1).create();
        new PostingBuilder(jdbc).id("order-r1")
                .title("Backend Developer").company("Acme Repost")
                .cities(CITY).skills("java", "sql").postedDaysAgo(2).create();
        new PostingBuilder(jdbc).id("order-c")
                .title("Java Developer").company("Company C")
                .cities(CITY).skills("java", "sql").postedDaysAgo(3).create();

        // r1's repost in capitals, and older, so it loses. Kept, it would rank between c and d,
        // inside the limit: a repost rule that stopped ignoring case changes the list.
        new PostingBuilder(jdbc).id("order-r2")
                .title("BACKEND DEVELOPER").company("ACME REPOST")
                .cities(CITY).skills("java", "sql").postedDaysAgo(4).create();

        // The builder always writes a date, so the null is set afterwards.
        new PostingBuilder(jdbc).id("order-d")
                .title("Database Engineer").company("Company D")
                .cities(CITY).skills("java", "sql").postedDaysAgo(3).create();
        jdbc.sql("UPDATE analytics.fct_postings SET posted_date = NULL WHERE posting_id = :id")
                .param("id", "order-d")
                .update();

        // One match, the same day: the posting id decides, e1 before e2.
        new PostingBuilder(jdbc).id("order-e1")
                .title("Frontend Engineer").company("Company E1")
                .cities(CITY).skills("java").postedDaysAgo(6).create();
        new PostingBuilder(jdbc).id("order-e2")
                .title("Fullstack Developer").company("Company E2")
                .cities(CITY).skills("java").postedDaysAgo(6).create();
        new PostingBuilder(jdbc).id("order-f")
                .title("Systems Engineer").company("Company F")
                .cities(CITY).skills("java").postedDaysAgo(10).create();

        // The ninth: cut by the limit.
        new PostingBuilder(jdbc).id("order-g")
                .title("Test Engineer").company("Company G")
                .cities(CITY).skills("java").postedDaysAgo(20).create();

        // Would be first, but in another city.
        new PostingBuilder(jdbc).id("order-x")
                .title("Senior Developer").company("Company X")
                .cities("elsewhere").skills("java", "sql", "go").postedDaysAgo(1).create();
    }
}

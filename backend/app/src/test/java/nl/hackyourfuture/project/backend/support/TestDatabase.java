package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * Puts the database back to its starting state between tests: empty module schemas in project_db,
 * application-service's saved jobs in apps_db, the baseline mart in jobs_db, and the DynamoDB
 * score table.
 *
 * <p>Truncate rather than roll back a transaction. The tests drive the application over HTTP,
 * so the writes happen on the server's own connections and there is no test-side transaction
 * to roll back.
 */
public final class TestDatabase {

    private static final List<String> MART_TABLES = List.of(
            "analytics.fct_postings",
            "analytics.fct_postings_cities",
            "analytics.fct_postings_skills");

    // Flyway's own bookkeeping. Truncating it would make the next context start re-run every
    // migration against a schema that already has the objects.
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private static final JdbcClient JDBC = JdbcClient.create(PostgresContainer.dataSource());
    private static final JdbcClient JOBS_JDBC = JdbcClient.create(PostgresContainer.jobsDataSource());
    private static final JdbcClient APPS_JDBC = JdbcClient.create(PostgresContainer.appsDataSource());
    private static final String SEED = SqlScripts.read("fixtures/analytics-seed.sql");
    private static volatile boolean bridged;

    private TestDatabase() {
    }

    /** A JdbcClient on the test container, for fixtures and for asserting on stored rows. */
    public static JdbcClient jdbc() {
        return JDBC;
    }

    /** A JdbcClient on the jobs database, where the mart is. */
    public static JdbcClient jobsJdbc() {
        return JOBS_JDBC;
    }

    /** A JdbcClient on the apps database, where application-service keeps saved jobs (Day 25). */
    public static JdbcClient appsJdbc() {
        return APPS_JDBC;
    }

    /**
     * A user's saved jobs in apps_db, once they number {@code expected} or after 10 s, whichever
     * comes first. A deleted account's rows go by user.deleted through the event bus (Day 25), so
     * they are gone a little after the delete answers, not with it.
     */
    public static long savedJobsOf(UUID userId, long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        long count = countSavedJobs(userId);
        while (count != expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return count;
            }
            count = countSavedJobs(userId);
        }
        return count;
    }

    private static long countSavedJobs(UUID userId) {
        return APPS_JDBC.sql("SELECT count(*) FROM applications.saved_jobs WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    public static void reset() {
        bridgeSavedJobs();
        List<String> tables = applicationTables();
        if (!tables.isEmpty()) {
            // One statement: TRUNCATE takes a table list, and CASCADE follows the foreign keys
            // so the order the tables come back in does not matter.
            JDBC.sql("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").update();
        }
        // apps_db's saved jobs: the foreign table is not in pg_tables, so the truncate above does not reach it.
        APPS_JDBC.sql("TRUNCATE TABLE applications.saved_jobs").update();
        JOBS_JDBC.sql("TRUNCATE TABLE " + String.join(", ", MART_TABLES)).update();
        PostgresContainer.executeInJobs(SEED);
        ScoreTable.empty();
    }

    /**
     * The monolith no longer writes saved_jobs (Day 25); application-service does, in apps_db.
     * The contract tests insert through {@code jdbc()}, so project_db's table becomes a
     * postgres_fdw foreign table over apps_db's, with {@code job_state}'s default since their
     * inserts omit it. Created after the monolith's migrations ran (a context exists by the first
     * reset): {@code DROP TABLE} refuses a foreign table, so a migration dropping the table must
     * run before this. Nothing in {@code main/} sees it.
     */
    private static synchronized void bridgeSavedJobs() {
        if (bridged) {
            return;
        }
        // Starts the container, whose Flyway creates apps_db's applications.saved_jobs.
        ApplicationService.port();

        String rolePassword = PostgresContainer.rolePassword();

        String relkind = JDBC.sql("""
                        SELECT relkind FROM pg_class WHERE oid = to_regclass('applications.saved_jobs')
                        """)
                .query(String.class)
                .optional()
                .orElse(null);

        if ("f".equals(relkind)) {
            bridged = true;
            return;
        }

        if (relkind != null) {
            JDBC.sql("DROP TABLE applications.saved_jobs").update();
        }

        JDBC.sql("CREATE EXTENSION IF NOT EXISTS postgres_fdw").update();
        JDBC.sql("CREATE SERVER IF NOT EXISTS apps_db FOREIGN DATA WRAPPER postgres_fdw "
                + "OPTIONS (host 'localhost', port '5432', dbname 'apps_db')").update();
        JDBC.sql("CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER apps_db "
                + "OPTIONS (user 'applications_user', password '" + rolePassword + "')").update();
        JDBC.sql("""
                CREATE FOREIGN TABLE applications.saved_jobs (
                    user_id UUID NOT NULL,
                    posting_id TEXT NOT NULL,
                    job_state applications.job_state NOT NULL DEFAULT 'SAVED'
                ) SERVER apps_db OPTIONS (schema_name 'applications', table_name 'saved_jobs')
                """).update();

        bridged = true;
    }

    /**
     * Read from the catalogue instead of a hardcoded list, so a table added by a future
     * migration is cleaned up without anyone remembering to edit this class.
     */
    private static List<String> applicationTables() {
        return JDBC.sql("""
                        SELECT quote_ident(schemaname) || '.' || quote_ident(tablename)
                        FROM pg_tables
                        WHERE schemaname IN (:schemas) AND tablename <> :flywayHistory
                        """)
                .param("schemas", PostgresContainer.TABLE_SCHEMAS)
                .param("flywayHistory", FLYWAY_HISTORY)
                .query(String.class)
                .list();
    }
}
